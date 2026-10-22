import { Component, computed, effect, inject, output, signal, untracked } from '@angular/core';
import { DatabaseView, Engine, TableView } from './engine';
import { Catalog, up } from './catalog';
import { Doc, Workbench } from './workbench';
import { DocTabs } from './doc-tabs';
import { Panes } from './panes';
import { Icon } from './icon';
import { StatusDot } from './status-dot';

/**
 * The Workbench's Data page: every database of the project on the left (Postgres tables, Redis keys), and what
 * you opened from it as tabs on the right. Everything is read and written through the engine.
 */
@Component({
  selector: 'dd-data',
  imports: [Icon, StatusDot, DocTabs, Panes],
  template: `
    <aside class="tree">
      <input class="find" placeholder="Find a table…" [value]="find()" (input)="find.set($any($event.target).value)" />
      @for (d of catalog.databases(); track d.service) {
        <div class="db">
          <button class="dbhead" (click)="toggle(d.service)">
            <dd-icon [name]="collapsed().has(d.service) ? 'chevronRight' : 'chevronDown'" [size]="12" />
            <dd-icon [name]="d.kind === 'redis' ? 'key' : 'database'" [size]="13" />
            <span class="dbname" [title]="d.service">{{ d.kind === 'sqlite' ? fileName(d) : d.service }}</span>
            @if (d.kind === 'sqlite') { <span class="kind mono">sqlite</span> } @else { <dd-dot [label]="d.state.label" /> }
          </button>
          @if (!collapsed().has(d.service)) {
            @if (d.kind === 'sqlite') {
              <div class="dbinfo mono" [title]="d.database">{{ d.service.replaceAll(':', '/') }}</div>
            } @else {
              <div class="dbinfo mono" [title]="d.kind">{{ d.kind }} · {{ d.user ? d.user + '@' : '' }}:{{ d.port }}{{ d.database ? '/' + d.database : '' }}</div>
            }
            @if (!isUp(d)) {
              <div class="down">
                Not running.
                <button class="sm" (click)="startDb(d)"><dd-icon name="play" [size]="10" /> Start</button>
              </div>
            } @else if (d.kind === 'mongo') {
              @for (m of mongo()[d.service] ?? []; track m.name) {
                <button class="item" (click)="toggleMongo(d.service, m.name)" [title]="m.name">
                  <dd-icon [name]="mongoOpen().has(d.service + '/' + m.name) ? 'chevronDown' : 'chevronRight'" [size]="11" /><span class="tname">{{ m.name }}</span>
                </button>
                @if (mongoOpen().has(d.service + '/' + m.name)) {
                  @for (c of m.collections ?? []; track c.name) {
                    <button class="item sub" [class.on]="isMongo(d.service, m.name, c.name)" (click)="open({ kind: 'mongo', service: d.service, db: m.name, collection: c.name })">
                      <dd-icon name="table" [size]="13" /><span class="tname">{{ c.name }}</span><span class="count mono">{{ c.documents }}</span>
                    </button>
                  } @empty { <div class="dbinfo">no collections</div> }
                }
              } @empty {
                <button class="item" (click)="loadMongo(d.service)"><dd-icon name="restart" [size]="13" /><span class="tname">Load databases</span></button>
              }
              <button class="item" [class.on]="isOpenKind('backups', d.service)" (click)="open({ kind: 'backups', service: d.service })">
                <dd-icon name="archive" [size]="13" /><span class="tname">Backups</span>
              </button>
            } @else if (d.kind === 'objects') {
              @for (b of buckets()[d.service] ?? []; track b) {
                <button class="item" [class.on]="isOpenNamed('bucket', d.service, b)" (click)="open({ kind: 'bucket', service: d.service, bucket: b })">
                  <dd-icon name="folder" [size]="13" /><span class="tname">{{ b }}</span>
                </button>
              } @empty {
                <div class="dbinfo">no buckets yet</div>
              }
              <button class="item new" (click)="newBucket(d.service)" title="Create a bucket"><dd-icon name="plus" [size]="13" /><span class="tname">New bucket</span></button>
            } @else if (d.kind === 'search') {
              @for (i of indices()[d.service] ?? []; track i.name) {
                <button class="item" [class.on]="isOpenNamed('index', d.service, i.name)" (click)="open({ kind: 'index', service: d.service, index: i.name })" [title]="i.health + ' · ' + i.documents + ' documents'">
                  <dd-icon name="search" [size]="13" /><span class="tname">{{ i.name }}</span><span class="count mono">{{ i.documents }}</span>
                </button>
              } @empty {
                <div class="dbinfo">no indices yet</div>
              }
              <button class="item" (click)="loadIndices(d.service)"><dd-icon name="restart" [size]="13" /><span class="tname">Reload</span></button>
            } @else if (d.kind === 'redis') {
              <button class="item" (click)="open({ kind: 'redis', service: d.service, pattern: '*' })">
                <dd-icon name="key" [size]="13" /><span class="tname">Browse keys</span>
              </button>
              <button class="item" [class.on]="isTool(d.service, 'memory')" (click)="open({ kind: 'redis-tools', service: d.service, tool: 'memory' })">
                <dd-icon name="archive" [size]="13" /><span class="tname">Memory</span>
              </button>
              <button class="item" [class.on]="isTool(d.service, 'slowlog')" (click)="open({ kind: 'redis-tools', service: d.service, tool: 'slowlog' })">
                <dd-icon name="timeline" [size]="13" /><span class="tname">Slow log</span>
              </button>
              <button class="item" [class.on]="isTool(d.service, 'monitor')" (click)="open({ kind: 'redis-tools', service: d.service, tool: 'monitor' })">
                <dd-icon name="pulse" [size]="13" /><span class="tname">Live commands</span>
              </button>
              <button class="item" [class.on]="isTool(d.service, 'pubsub')" (click)="open({ kind: 'redis-tools', service: d.service, tool: 'pubsub' })">
                <dd-icon name="radio" [size]="13" /><span class="tname">Pub/Sub</span>
              </button>
              <button class="item" [class.on]="isTool(d.service, 'stats')" (click)="open({ kind: 'redis-tools', service: d.service, tool: 'stats' })">
                <dd-icon name="timeline" [size]="13" /><span class="tname">Server stats</span>
              </button>
            } @else {
              <button class="item new" (click)="open({ kind: 'sql', service: d.service })">
                <dd-icon name="sql" [size]="13" /><span class="tname">New SQL query</span>
              </button>
              <button class="item new" (click)="open({ kind: 'design', service: d.service, schema: d.kind === 'postgres' ? 'public' : d.kind === 'sqlite' ? 'main' : (d.database ?? '') , table: '' })" title="Create a table with a form">
                <dd-icon name="plus" [size]="13" /><span class="tname">New table</span>
              </button>
              <button class="item" [class.on]="isOpenKind('diagram', d.service)" (click)="open({ kind: 'diagram', service: d.service, schema: d.kind === 'postgres' ? 'public' : '' })" title="Every table and how they link">
                <dd-icon name="graph" [size]="13" /><span class="tname">Diagram</span>
              </button>
              @if (d.features.includes('stats')) {
                <button class="item" [class.on]="isOpenKind('stats', d.service)" (click)="open({ kind: 'stats', service: d.service })" title="Size, table use, hints, users and their rights">
                  <dd-icon name="pulse" [size]="13" /><span class="tname">Size and users</span>
                </button>
              }
              @if (d.features.includes('slow')) {
                <button class="item" [class.on]="isOpenKind('slow', d.service)" (click)="open({ kind: 'slow', service: d.service })" title="Postgres's own list of its slowest queries">
                  <dd-icon name="timeline" [size]="13" /><span class="tname">Slowest queries</span>
                </button>
              }
              @if (d.features.includes('activity')) {
                <button class="item" [class.on]="isOpenKind('activity', d.service)" (click)="open({ kind: 'activity', service: d.service })">
                  <dd-icon name="pulse" [size]="13" /><span class="tname">Sessions</span>
                </button>
              }
              @if (d.features.includes('backup')) {
                <button class="item" [class.on]="isOpenKind('backups', d.service)" (click)="open({ kind: 'backups', service: d.service })">
                  <dd-icon name="archive" [size]="13" /><span class="tname">Backups</span>
                </button>
              }
              @if (savedQueries(d.service).length) {
                <div class="schema">saved queries</div>
                @for (q of savedQueries(d.service); track q.id) {
                  <div class="item" role="button" [title]="q.sql" (click)="open({ kind: 'sql', service: d.service, saved: q })">
                    <dd-icon name="bookmark" [size]="13" /><span class="tname">{{ q.name }}</span>
                    <button class="ghost icon sm del" title="Delete this saved query" (click)="$event.stopPropagation(); deleteQuery(d.service, q.id, q.name)"><dd-icon name="trash" [size]="11" /></button>
                  </div>
                }
              }
              @if (catalog.errors()[d.service]; as e) {
                <div class="err small">{{ e }}</div>
              }
              @for (g of groups(d.service); track g.schema) {
                <div class="schema">{{ g.schema }}</div>
                @for (t of g.tables; track t.name) {
                  <button class="item" [class.on]="isOpen(d.service, t)" (click)="open({ kind: 'table', service: d.service, schema: t.schema, table: t.name })">
                    <dd-icon [name]="t.view ? 'eye' : 'table'" [size]="13" />
                    <span class="tname">{{ t.name }}</span>
                    <span class="count mono">{{ t.view ? 'view' : t.rows < 0 ? '?' : t.rows.toLocaleString() }}</span>
                  </button>
                }
              }
            }
          }
        </div>
      } @empty {
        <div class="empty">
          <h3>No database found</h3>
          <p>DevDesk finds Postgres, MySQL, MariaDB and Redis containers with a published port by itself (from the image name, and the user, password and database from the same variables the image reads, like <code>POSTGRES_PASSWORD</code> or <code>MYSQL_ROOT_PASSWORD</code>), and SQLite files (<code>*.db</code>, <code>*.sqlite</code>) in the project folder.</p>
        </div>
      }
      <span class="grow"></span>
      <button class="ghost sm refresh" (click)="catalog.refresh()"><dd-icon name="restart" [size]="12" /> Refresh</button>
    </aside>
    <section class="main">
      <dd-doc-tabs (show)="show.emit($event)" />
      <dd-panes [left]="current()" (toast)="toast.emit($event)" (show)="show.emit($event)">
        <div class="none">
          <dd-icon name="database" [size]="28" />
          <p>Open a table or a key pattern on the left, or start a SQL query. Each one opens as a tab.</p>
          <p class="dim"><kbd>Ctrl K</kbd> finds tables by name · <kbd>Ctrl W</kbd> closes a tab · <kbd>Ctrl S</kbd> saves edits</p>
        </div>
      </dd-panes>
    </section>
  `,
  styles: `
    :host { display: grid; grid-template-columns: 260px 1fr; height: 100%; min-height: 0; }
    .tree { border-right: 1px solid var(--border); padding: 10px 8px; overflow: auto; display: flex; flex-direction: column; gap: 2px; background: var(--bg-1); }
    .find { margin: 0 2px 8px; height: 28px; }
    .db { display: flex; flex-direction: column; gap: 1px; margin-bottom: 6px; }
    .dbhead { justify-content: flex-start; gap: 6px; background: transparent; border-color: transparent; height: 28px; padding: 0 6px; }
    .dbhead:hover:not(:disabled) { background: var(--bg-2); border-color: transparent; }
    .dbname { flex: 1; text-align: left; font-weight: 600; }
    .dbinfo { color: var(--text-3); font-size: var(--fs-xs); padding: 0 8px 4px 32px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .down { display: flex; align-items: center; gap: 8px; color: var(--text-3); font-size: var(--fs-sm); padding: 2px 8px 2px 32px; }
    .schema { font: 500 var(--fs-xs) var(--font-mono); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; padding: 8px 6px 4px 22px; }
    .item { justify-content: flex-start; background: transparent; border-color: transparent; height: 27px; padding: 0 8px 0 22px; color: var(--text-2); gap: 8px; }
    .item:hover:not(:disabled) { background: var(--bg-2); color: var(--text-1); border-color: transparent; }
    .item.on { background: var(--accent-weak); color: var(--text-1); }
    .item.sub { padding-left: 36px; }
    div.item { display: flex; align-items: center; border-radius: var(--radius); cursor: pointer; flex: none; }
    .item.on dd-icon, .item.new dd-icon { color: var(--accent); }
    .tname { flex: 1; text-align: left; overflow: hidden; text-overflow: ellipsis; font-family: var(--font-mono); font-size: var(--fs-sm); }
    .count { color: var(--text-3); font-size: var(--fs-xs); }
    .kind { color: var(--text-3); font-size: var(--fs-xs); }
    .del { opacity: 0; width: 20px; height: 20px; }
    .item:hover .del { opacity: 1; }
    .grow { flex: 1; }
    .refresh { margin-top: 8px; align-self: flex-start; }
    .empty { padding: 12px 8px; color: var(--text-2); font-size: var(--fs-sm); }
    .empty h3 { color: var(--text-1); margin: 0 0 6px; font-size: var(--fs-md); }
    code { font-family: var(--font-mono); color: var(--text-1); }
    .main { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
    .main > :last-child { flex: 1; min-height: 0; }
    .none { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px; color: var(--text-2); text-align: center; padding: 32px; }
    .none dd-icon { color: var(--accent); margin-bottom: 8px; }
    .none p { margin: 0; }
    .dim { color: var(--text-3); font-size: var(--fs-sm); margin-top: 6px !important; }
    .err { color: var(--err); padding: 4px 8px 4px 22px; }
    .small { font-size: var(--fs-sm); }
  `,
})
export class DataPanel {
  readonly engine = inject(Engine);
  readonly catalog = inject(Catalog);
  readonly wb = inject(Workbench);
  readonly show = output<Doc>();
  readonly toast = output<string>();
  readonly find = signal('');
  readonly collapsed = signal<Set<string>>(new Set());

  /** The tab shown here: the active one if it belongs to this page, else the last one that did. */
  readonly current = computed(() => {
    void this.wb.activeId();
    return this.wb.lastOn('data');
  });

  constructor() {
    // Re-read the list when the services' states change (a stopped database becomes reachable).
    effect(() => {
      void (this.engine.project()?.services ?? []).map((s) => s.name + s.state.label).join();
      untracked(async () => {
        await this.catalog.refresh();
        // MongoDB's databases are listed as soon as it's up (its collections when a database is opened).
        for (const d of this.catalog.databases().filter((x) => x.kind === 'mongo' && this.isUp(x) && !this.mongo()[x.service])) {
          await this.loadMongo(d.service);
        }
        // S3 buckets and search indices are listed as soon as their service is up.
        for (const d of this.catalog.databases().filter((x) => x.kind === 'objects' && this.isUp(x) && !this.buckets()[x.service])) {
          await this.loadBuckets(d.service);
        }
        for (const d of this.catalog.databases().filter((x) => x.kind === 'search' && this.isUp(x) && !this.indices()[x.service])) {
          await this.loadIndices(d.service);
        }
      });
    });
  }

  groups(service: string): { schema: string; tables: TableView[] }[] {
    const f = this.find().toLowerCase();
    const by = new Map<string, TableView[]>();
    for (const t of this.catalog.tables()[service] ?? []) {
      if (f && !t.name.toLowerCase().includes(f)) continue;
      by.set(t.schema, [...(by.get(t.schema) ?? []), t]);
    }
    return [...by.entries()].map(([schema, tables]) => ({ schema, tables }));
  }

  isUp = up;

  savedQueries(service: string) {
    return this.catalog.queries()[service] ?? [];
  }

  fileName(d: DatabaseView): string {
    return d.service.split(':').pop() ?? d.service;
  }

  isTool(service: string, tool: string): boolean {
    const s = this.current()?.spec;
    return s?.kind === 'redis-tools' && s.service === service && s.tool === tool;
  }

  /** S3 buckets and search indices, per service. */
  readonly buckets = signal<Record<string, string[]>>({});
  readonly indices = signal<Record<string, { name: string; documents: number; health: string }[]>>({});

  async loadBuckets(service: string): Promise<void> {
    const r = await this.engine.buckets(service);
    if (r.ok) this.buckets.update((b) => ({ ...b, [service]: r.value.map((x) => x.name) }));
    else this.toast.emit(r.error);
  }

  async newBucket(service: string): Promise<void> {
    const name = prompt('Name of the new bucket (lowercase letters, digits, dots and dashes):');
    if (!name) return;
    const r = await this.engine.createBucket(service, name.trim());
    if (!r.ok) return this.toast.emit(r.error);
    await this.loadBuckets(service);
    this.open({ kind: 'bucket', service, bucket: name.trim() });
  }

  async loadIndices(service: string): Promise<void> {
    const r = await this.engine.searchIndices(service);
    if (r.ok) this.indices.update((x) => ({ ...x, [service]: r.value }));
    else this.toast.emit(r.error);
  }

  isOpenNamed(kind: 'bucket' | 'index', service: string, name: string): boolean {
    const s = this.current()?.spec;
    if (s?.kind === 'bucket' && kind === 'bucket') return s.service === service && s.bucket === name;
    if (s?.kind === 'index' && kind === 'index') return s.service === service && s.index === name;
    return false;
  }

  /** MongoDB databases and, once opened, their collections. */
  readonly mongo = signal<Record<string, { name: string; collections?: { name: string; documents: number }[] }[]>>({});
  readonly mongoOpen = signal<Set<string>>(new Set());

  async loadMongo(service: string): Promise<void> {
    const r = await this.engine.mongoDatabases(service);
    if (!r.ok) return this.toast.emit(r.error);
    this.mongo.update((m) => ({ ...m, [service]: r.value.map((d) => ({ name: d.name })) }));
  }

  async toggleMongo(service: string, db: string): Promise<void> {
    const key = `${service}/${db}`;
    const open = new Set(this.mongoOpen());
    if (open.has(key)) open.delete(key);
    else {
      open.add(key);
      const r = await this.engine.mongoCollections(service, db);
      if (r.ok) this.mongo.update((m) => ({ ...m, [service]: (m[service] ?? []).map((x) => (x.name === db ? { ...x, collections: r.value } : x)) }));
    }
    this.mongoOpen.set(open);
  }

  isMongo(service: string, db: string, collection: string): boolean {
    const s = this.current()?.spec;
    return s?.kind === 'mongo' && s.service === service && s.db === db && s.collection === collection;
  }

  isOpenKind(kind: 'activity' | 'backups' | 'diagram' | 'stats' | 'slow', service: string): boolean {
    const s = this.current()?.spec;
    return s?.kind === kind && s.service === service;
  }

  async deleteQuery(service: string, id: string, name: string): Promise<void> {
    if (!confirm(`Delete the saved query "${name}"?`)) return;
    const r = await this.engine.deleteQuery(service, id);
    if (r.ok) this.catalog.setQueries(service, r.value);
  }

  isOpen(service: string, t: TableView): boolean {
    const s = this.current()?.spec;
    return s?.kind === 'table' && s.service === service && s.schema === t.schema && s.table === t.name;
  }

  open(spec: Parameters<Workbench['open']>[0]): void {
    this.wb.open(spec);
  }

  toggle(service: string): void {
    this.collapsed.update((c) => {
      const next = new Set(c);
      next.has(service) ? next.delete(service) : next.add(service);
      return next;
    });
  }

  async startDb(d: DatabaseView): Promise<void> {
    await this.engine.serviceAction(d.service, 'start');
  }
}
