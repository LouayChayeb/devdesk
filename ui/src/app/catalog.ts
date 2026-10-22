import { Injectable, inject, signal } from '@angular/core';
import { DatabaseView, Engine, SavedQueryView, TableView } from './engine';

/** What the Workbench can open: the project's databases and their tables, as the engine reports them. */
@Injectable({ providedIn: 'root' })
export class Catalog {
  private readonly engine = inject(Engine);
  readonly databases = signal<DatabaseView[]>([]);
  readonly tables = signal<Record<string, TableView[]>>({});
  readonly errors = signal<Record<string, string>>({});
  /** Saved SQL queries per database, from the engine's file. */
  readonly queries = signal<Record<string, SavedQueryView[]>>({});
  /** Columns seen so far per database, for SQL suggestions. */
  readonly columns = signal<Record<string, string[]>>({});
  private root: string | undefined;

  /** Re-reads the database list; tables of databases that are up are (re)loaded. */
  async refresh(): Promise<void> {
    const root = this.engine.project()?.root;
    if (root !== this.root) {
      this.root = root;
      this.tables.set({});
      this.errors.set({});
      this.columns.set({});
      this.queries.set({});
    }
    const list = await this.engine.databases();
    const before = this.databases();
    this.databases.set(list);
    for (const d of list.filter((x) => !['redis', 'mongo', 'objects', 'search'].includes(x.kind))) {
      const was = before.find((b) => b.service === d.service);
      const cameUp = up(d) && (!was || !up(was));
      if (cameUp || (up(d) && !this.tables()[d.service])) await this.loadTables(d.service);
      if (!this.queries()[d.service]) await this.loadQueries(d.service);
    }
  }

  async loadQueries(service: string): Promise<void> {
    const r = await this.engine.savedQueries(service);
    if (r.ok) this.setQueries(service, r.value);
  }

  setQueries(service: string, list: SavedQueryView[]): void {
    this.queries.update((q) => ({ ...q, [service]: list }));
  }

  database(service: string): DatabaseView | undefined {
    return this.databases().find((d) => d.service === service);
  }

  has(service: string, feature: DatabaseView['features'][number]): boolean {
    return !!this.database(service)?.features.includes(feature);
  }

  async loadTables(service: string): Promise<void> {
    const r = await this.engine.tables(service);
    this.tables.update((t) => ({ ...t, [service]: r.ok ? r.value : [] }));
    this.errors.update(({ [service]: _, ...rest }) => (r.ok ? rest : { ...rest, [service]: r.error }));
  }

  rememberColumns(service: string, names: string[]): void {
    this.columns.update((c) => ({ ...c, [service]: [...new Set([...(c[service] ?? []), ...names])] }));
  }
}

/** Reachable now: a running service, or a SQLite file (always there). */
export function up(d: DatabaseView): boolean {
  return ['healthy', 'running', 'unhealthy', 'file'].includes(d.state.label);
}
