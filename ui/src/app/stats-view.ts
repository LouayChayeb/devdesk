import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Engine, StatsView as Stats } from './engine';
import { Doc, Workbench } from './workbench';
import { Icon } from './icon';
import { bytes } from './format';

class StatsState {
  readonly stats = signal<Stats | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly sort = signal<'size' | 'rows' | 'seq' | 'name'>('size');
}

/** A database's size, how each table is used, DevDesk's hints, and the users with their rights. Read-only. */
@Component({
  selector: 'dd-stats-view',
  imports: [Icon],
  template: `
    @let st = s();
    <div class="bar">
      <span class="mono where">{{ service() }}</span>
      @if (st.stats(); as x) {
        <span class="big mono">{{ size(x.databaseBytes) }}</span><span class="dim small">on disk · {{ x.tables.length }} tables</span>
      }
      <span class="grow"></span>
      @if (logging(); as l) {
        @if (l.supported) {
          <span class="dim small" title="When on, Postgres logs every query with its time: the API page shows the SQL each request ran">query logging {{ l.on ? 'on' : 'off' }}</span>
          <button class="ghost sm" (click)="toggleLogging(!l.on)">{{ l.on ? 'Turn off' : 'Turn on' }}</button>
        }
      }
      <button class="ghost sm" [class.busy]="st.busy()" (click)="load()"><dd-icon name="restart" [size]="12" /> Refresh</button>
    </div>
    @if (st.error(); as e) {
      <div class="error">{{ e }}</div>
    }
    @if (st.stats(); as x) {
      <div class="scroll">
        @if (x.hints.length) {
          <section>
            @for (h of x.hints; track $index) {
              <div class="hint"><dd-icon name="diagnosis" [size]="14" /><span>{{ h.text }}</span></div>
            }
          </section>
        }
        <section>
          <h4>Tables</h4>
          <table>
            <thead>
              <tr>
                <th class="click" (click)="st.sort.set('name')">table</th>
                <th class="num click" (click)="st.sort.set('rows')">rows</th>
                <th class="click" (click)="st.sort.set('size')" colspan="2">size (data + indexes)</th>
                <th class="num">indexes</th>
                <th class="num click" (click)="st.sort.set('seq')" title="Times the whole table was read from start to end, since the statistics were reset">whole-table reads</th>
                <th class="num" title="Times rows were found through an index">index reads</th>
                @if (hasDead()) { <th class="num" title="Old row versions waiting for VACUUM">dead rows</th> }
                @if (hasVacuum()) { <th>last vacuum</th> }
              </tr>
            </thead>
            <tbody>
              @for (t of tables(); track t.schema + t.name) {
                <tr>
                  <td class="mono"><a (click)="open(t.schema, t.name)">{{ label(t.schema, t.name) }}</a></td>
                  <td class="num mono">{{ n(t.rows) }}</td>
                  <td class="barcell"><span class="fill" [style.width.%]="share(t.totalBytes)"></span></td>
                  <td class="num mono">{{ size(t.totalBytes) }}</td>
                  <td class="num mono dim">{{ size(t.indexBytes) }}</td>
                  <td class="num mono" [class.warn]="t.seqScans > 500 && t.rows > 10000">{{ n(t.seqScans) }}</td>
                  <td class="num mono">{{ n(t.indexScans) }}</td>
                  @if (hasDead()) { <td class="num mono">{{ n(t.deadRows) }}</td> }
                  @if (hasVacuum()) { <td class="mono dim small">{{ t.lastVacuum ? when(t.lastVacuum) : 'never' }}</td> }
                </tr>
              }
            </tbody>
          </table>
          <p class="dim small">Read counts start over when the database restarts or its statistics are reset.</p>
        </section>
        <section>
          <h4>Users and rights</h4>
          @for (r of x.roles; track r.name) {
            <div class="role">
              <div class="rname mono">{{ r.name }}
                @for (a of r.attributes; track a) { <span class="attr" [class.su]="a === 'superuser'">{{ a }}</span> }
              </div>
              @if (r.memberOf.length) { <div class="dim small">member of {{ r.memberOf.join(', ') }}</div> }
              @for (p of r.privileges; track p) { <div class="priv mono">{{ p }}</div> }
              @if (!r.privileges.length && !r.attributes.length) { <div class="dim small">no rights of its own</div> }
            </div>
          } @empty {
            <p class="dim small">{{ isSqlite() ? 'SQLite has no users: whoever can read the file can read everything in it.' : 'No users listed: DevDesk\\'s login may not be allowed to read them.' }}</p>
          }
        </section>
      </div>
    } @else if (!st.error()) {
      <div class="pad dim">Reading statistics…</div>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 10px; padding: 8px 14px; border-bottom: 1px solid var(--border); }
    .where { color: var(--text-2); }
    .big { font-size: 15px; color: var(--text-1); font-weight: 600; }
    .grow { flex: 1; }
    .error { margin: 10px 14px; padding: 10px 12px; background: var(--err-bg); border-radius: var(--radius); font-size: var(--fs-sm); }
    .scroll { overflow: auto; flex: 1; padding: 6px 14px 20px; }
    section { margin-top: 10px; }
    h4 { margin: 8px 0; font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; }
    .hint { display: flex; gap: 8px; align-items: flex-start; padding: 8px 10px; margin-bottom: 6px; background: var(--warn-bg, rgba(230, 180, 80, 0.08)); border: 1px solid rgba(230, 180, 80, 0.35); border-radius: var(--radius); font-size: var(--fs-sm); }
    .hint dd-icon { color: var(--warn); flex: none; margin-top: 2px; }
    table { border-collapse: collapse; width: 100%; font-size: var(--fs-sm); }
    th { text-align: left; color: var(--text-3); font-weight: 500; padding: 5px 8px; border-bottom: 1px solid var(--border); white-space: nowrap; }
    td { padding: 4px 8px; border-bottom: 1px solid var(--border); white-space: nowrap; }
    .num { text-align: right; }
    .click { cursor: pointer; }
    .click:hover { color: var(--text-1); }
    a { color: var(--accent); cursor: pointer; }
    .barcell { width: 22%; }
    .fill { display: block; height: 8px; border-radius: 2px; background: var(--accent); opacity: 0.6; min-width: 2px; }
    .warn { color: var(--warn); }
    .role { padding: 8px 10px; border: 1px solid var(--border); border-radius: var(--radius); margin-bottom: 6px; display: grid; gap: 3px; }
    .rname { color: var(--text-1); display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
    .attr { font-family: var(--font-ui); font-size: var(--fs-xs); color: var(--text-2); background: var(--bg-2); padding: 0 6px; border-radius: 3px; }
    .attr.su { color: var(--warn); }
    .priv { font-size: var(--fs-xs); color: var(--text-2); }
    .pad { padding: 16px; }
  `,
})
export class StatsViewComponent {
  readonly engine = inject(Engine);
  readonly wb = inject(Workbench);
  readonly doc = input.required<Doc>();
  readonly show = output<Doc>();

  readonly s = computed(() => {
    const d = this.doc();
    if (!(d.state instanceof StatsState)) d.state = new StatsState();
    return d.state as StatsState;
  });
  readonly service = computed(() => (this.doc().spec as { service: string }).service);
  readonly tables = computed(() => {
    const x = this.s().stats();
    if (!x) return [];
    const by = this.s().sort();
    return [...x.tables].sort((a, b) =>
      by === 'name' ? `${a.schema}.${a.name}`.localeCompare(`${b.schema}.${b.name}`) : by === 'rows' ? b.rows - a.rows : by === 'seq' ? b.seqScans - a.seqScans : b.totalBytes - a.totalBytes,
    );
  });
  readonly hasDead = computed(() => (this.s().stats()?.tables ?? []).some((t) => t.deadRows >= 0));
  readonly hasVacuum = computed(() => this.hasDead());
  readonly biggest = computed(() => Math.max(1, ...(this.s().stats()?.tables ?? []).map((t) => t.totalBytes)));

  constructor() {
    effect(() => {
      const st = this.s();
      if (!st.stats()) untracked(() => this.load());
    });
  }

  readonly logging = signal<{ supported: boolean; on: boolean } | null>(null);

  async toggleLogging(on: boolean): Promise<void> {
    const r = await this.engine.setQueryLogging(this.service(), on);
    if (r.ok) this.logging.set({ supported: true, on });
  }

  async load(): Promise<void> {
    const st = this.s();
    this.engine.queryLogging(this.service()).then((r) => this.logging.set(r.ok ? r.value : null));
    st.busy.set(true);
    const r = await this.engine.stats(this.service());
    st.busy.set(false);
    if (r.ok) {
      st.stats.set(r.value);
      st.error.set(null);
    } else {
      st.error.set(r.error);
    }
  }

  open(schema: string, table: string): void {
    this.show.emit(this.wb.open({ kind: 'table', service: this.service(), schema, table }));
  }

  isSqlite(): boolean {
    return (this.s().stats()?.tables ?? []).every((t) => t.schema === 'main');
  }

  share(b: number): number {
    return b < 0 ? 0 : (100 * b) / this.biggest();
  }

  label(schema: string, name: string): string {
    return schema === 'public' || schema === 'main' ? name : `${schema}.${name}`;
  }

  size(n: number): string {
    return n < 0 ? '–' : n < 1024 ? `${n} B` : bytes(n);
  }

  n(x: number): string {
    return x < 0 ? '–' : x.toLocaleString('en-US');
  }

  when(iso: string): string {
    return new Date(iso).toLocaleString('en-GB', { hour12: false });
  }
}
