import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { DesignView as Design, Engine } from './engine';
import { Catalog } from './catalog';
import { Doc, Workbench } from './workbench';
import { Icon } from './icon';

type Col = Design['columns'][number];

/** The form's state: the table as it will look, plus the SQL preview once asked for. */
class DesignState {
  readonly loaded = signal(false);
  readonly name = signal('');
  readonly columns = signal<Col[]>([]);
  readonly indexes = signal<Design['indexes']>([]);
  readonly links = signal<Design['links']>([]);
  readonly preview = signal<{ sql: string[]; notes: string[] } | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
}

/** Type names offered while typing; any other type the database knows can be typed too. */
const TYPES: Record<string, string[]> = {
  postgres: ['integer', 'bigint', 'serial', 'bigserial', 'text', 'varchar(255)', 'boolean', 'numeric(10,2)', 'real', 'double precision', 'date', 'timestamptz', 'timestamp', 'uuid', 'jsonb', 'bytea', 'text[]'],
  mysql: ['int', 'bigint', 'varchar(255)', 'text', 'tinyint(1)', 'decimal(10,2)', 'double', 'date', 'datetime', 'timestamp', 'json', 'blob'],
  sqlite: ['integer', 'text', 'real', 'numeric', 'blob'],
};

/**
 * Create or change a table with a form. The engine compares the form with the table as it is and writes the SQL;
 * nothing runs before the user has seen that SQL.
 */
@Component({
  selector: 'dd-design-view',
  imports: [Icon],
  template: `
    @let st = s();
    <div class="bar">
      <span class="label">{{ isNew() ? 'New table in' : 'Change table' }}</span>
      <span class="mono dim">{{ spec().service }} · {{ spec().schema || 'main' }}.</span>
      <input class="mono tname" placeholder="table name" [value]="st.name()" (input)="st.name.set($any($event.target).value); st.preview.set(null)" />
      <span class="grow"></span>
      @if (!isNew()) {
        <button class="ghost sm danger-text" (click)="drop()" title="Delete the table and all its rows"><dd-icon name="trash" [size]="12" /> Delete table…</button>
      }
      <button class="primary sm" [class.busy]="st.busy()" (click)="previewSql()"><dd-icon name="sql" [size]="12" /> Preview SQL</button>
    </div>
    @if (st.error(); as e) {
      <div class="error">{{ e }}</div>
    }
    <datalist id="dd-types">@for (t of types(); track t) { <option [value]="t"></option> }</datalist>
    <div class="scroll">
      <h4>Columns</h4>
      <div class="grid head"><span>name</span><span>type</span><span title="NULL allowed">empty allowed</span><span>default (SQL)</span><span title="Part of the primary key">key</span><span></span></div>
      @for (c of st.columns(); track $index; let i = $index) {
        <div class="grid" [class.new]="!c.was">
          <input class="mono" [value]="c.name" (input)="setCol(i, 'name', $any($event.target).value)" placeholder="name" />
          <input class="mono" list="dd-types" [value]="c.type" (input)="setCol(i, 'type', $any($event.target).value)" placeholder="text" />
          <input type="checkbox" [checked]="c.nullable" (change)="setCol(i, 'nullable', $any($event.target).checked)" />
          <input class="mono" [value]="c.default ?? ''" (input)="setCol(i, 'default', $any($event.target).value || null)" placeholder="none" />
          <input type="checkbox" [checked]="c.primaryKey" (change)="setCol(i, 'primaryKey', $any($event.target).checked)" />
          <button class="ghost icon sm" title="Remove this column (its data goes with it)" (click)="removeCol(i)"><dd-icon name="close" [size]="12" /></button>
        </div>
      }
      <button class="ghost sm add" (click)="addCol()"><dd-icon name="plus" [size]="12" /> Column</button>

      <h4>Indexes <span class="dim small">(make lookups by these columns fast; unique = no two rows alike)</span></h4>
      @for (x of st.indexes(); track $index; let i = $index) {
        <div class="row">
          <input class="mono" [value]="x.name" [disabled]="!!x.was" (input)="setIndex(i, 'name', $any($event.target).value)" placeholder="orders_status" />
          <span class="dim">on</span>
          <input class="mono wide" [value]="x.columns.join(', ')" [disabled]="!!x.was" (input)="setIndex(i, 'columns', split($any($event.target).value))" placeholder="status, created_at" />
          <label class="dim"><input type="checkbox" [checked]="x.unique" [disabled]="!!x.was" (change)="setIndex(i, 'unique', $any($event.target).checked)" /> unique</label>
          <button class="ghost icon sm" title="Remove this index" (click)="removeIndex(i)"><dd-icon name="close" [size]="12" /></button>
        </div>
      }
      <button class="ghost sm add" (click)="addIndex()"><dd-icon name="plus" [size]="12" /> Index</button>

      <h4>Links <span class="dim small">(foreign keys: these columns hold the key of a row in another table)</span></h4>
      @for (l of st.links(); track $index; let i = $index) {
        <div class="row">
          <input class="mono" [value]="l.columns.join(', ')" [disabled]="!!l.was" (input)="setLink(i, 'columns', split($any($event.target).value))" placeholder="user_id" />
          <span class="dim">→</span>
          <select class="mono" [disabled]="!!l.was" (change)="setLink(i, 'toTable', $any($event.target).value)">
            <option value="">table…</option>
            @for (t of otherTables(); track t) { <option [value]="t" [selected]="t === l.toTable">{{ t }}</option> }
          </select>
          <input class="mono" [value]="l.toColumns.join(', ')" [disabled]="!!l.was" (input)="setLink(i, 'toColumns', split($any($event.target).value))" placeholder="id" />
          <input class="mono" [value]="l.name" [disabled]="!!l.was" (input)="setLink(i, 'name', $any($event.target).value)" placeholder="name" />
          <button class="ghost icon sm" title="Remove this link (the column stays)" (click)="removeLink(i)"><dd-icon name="close" [size]="12" /></button>
        </div>
      }
      <button class="ghost sm add" (click)="addLink()"><dd-icon name="plus" [size]="12" /> Link</button>

      @if (st.preview(); as p) {
        <h4>SQL that will run</h4>
        @if (p.sql.length) {
          <pre class="sql mono">{{ sqlText(p.sql) }}</pre>
          @for (n of p.notes; track n) { <div class="note">{{ n }}</div> }
          <div class="actions">
            <button class="ghost" (click)="st.preview.set(null)">Keep editing</button>
            <button class="primary" [class.busy]="st.busy()" (click)="apply()"><dd-icon name="play" [size]="12" /> Run this SQL</button>
          </div>
        } @else {
          <p class="dim">Nothing changed: the table already looks like this.</p>
        }
      }
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
    .label { color: var(--text-2); }
    .tname { width: 200px; }
    .grow { flex: 1; }
    .danger-text { color: var(--err); }
    .error { margin: 10px 14px 0; padding: 10px 12px; background: var(--err-bg); border-radius: var(--radius); font-size: var(--fs-sm); white-space: pre-wrap; }
    .scroll { overflow: auto; flex: 1; padding: 4px 14px 24px; }
    h4 { margin: 14px 0 6px; font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; }
    h4 .small { text-transform: none; letter-spacing: 0; }
    .grid { display: grid; grid-template-columns: minmax(120px, 1.2fr) minmax(120px, 1fr) 90px minmax(120px, 1fr) 40px 28px; gap: 6px; align-items: center; margin-bottom: 4px; }
    .grid.head { color: var(--text-3); font-size: var(--fs-xs); }
    .grid.new input:first-child { border-color: var(--accent-line); }
    .grid input[type='checkbox'] { justify-self: center; }
    .row { display: flex; gap: 6px; align-items: center; margin-bottom: 4px; }
    .row .wide { flex: 1; }
    .add { margin-top: 2px; }
    .sql { background: var(--bg-1); border: 1px solid var(--border); border-radius: var(--radius); padding: 10px 12px; white-space: pre-wrap; font-size: var(--fs-sm); }
    .note { color: var(--warn); font-size: var(--fs-sm); margin-top: 6px; }
    .actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 10px; }
  `,
})
export class DesignViewComponent {
  readonly engine = inject(Engine);
  readonly catalog = inject(Catalog);
  readonly wb = inject(Workbench);
  readonly doc = input.required<Doc>();
  readonly show = output<Doc>();
  readonly toast = output<string>();

  readonly s = computed(() => {
    const d = this.doc();
    if (!(d.state instanceof DesignState)) d.state = new DesignState();
    return d.state as DesignState;
  });
  readonly spec = computed(() => this.doc().spec as { service: string; schema: string; table: string });
  readonly isNew = computed(() => !this.spec().table);
  readonly kind = computed(() => this.catalog.databases().find((d) => d.service === this.spec().service)?.kind ?? 'postgres');
  readonly types = computed(() => TYPES[this.kind()] ?? TYPES['postgres']);
  readonly otherTables = computed(() => (this.catalog.tables()[this.spec().service] ?? []).filter((t) => !t.view).map((t) => t.name));

  constructor() {
    effect(() => {
      const st = this.s();
      if (!st.loaded()) untracked(() => this.load());
    });
  }

  /** An existing table starts as it is (each part remembers its current name); a new one with an id column. */
  async load(): Promise<void> {
    const st = this.s();
    const { service, schema, table } = this.spec();
    st.loaded.set(true);
    if (!table) {
      st.columns.set([{ name: 'id', type: this.kind() === 'mysql' ? 'int auto_increment' : this.kind() === 'sqlite' ? 'integer' : 'bigserial', nullable: false, default: null, primaryKey: true }]);
      return;
    }
    const r = await this.engine.structure(service, schema, table);
    if (!r.ok) {
      st.error.set(r.error);
      return;
    }
    st.name.set(table);
    st.columns.set(r.value.columns.map((c) => ({ was: c.name, name: c.name, type: c.type, nullable: c.nullable, default: c.default, primaryKey: c.primaryKey })));
    const keyCols = r.value.columns.filter((c) => c.primaryKey).map((c) => c.name).join();
    // The index behind the primary key comes and goes with the key: not listed here.
    st.indexes.set(r.value.indexes.filter((i) => !(i.unique && i.columns.join() === keyCols)).map((i) => ({ was: i.name, ...i })));
    st.links.set(r.value.foreignKeys.map((f) => ({ was: f.name, ...f })));
  }

  design(): Design {
    const st = this.s();
    return { schema: this.spec().schema, name: st.name().trim(), columns: st.columns(), indexes: st.indexes(), links: st.links() };
  }

  async previewSql(): Promise<void> {
    const st = this.s();
    st.busy.set(true);
    const r = await this.engine.previewDesign(this.spec().service, this.spec().table, this.design());
    st.busy.set(false);
    if (!r.ok) {
      st.error.set(r.error);
      st.preview.set(null);
      return;
    }
    st.error.set(null);
    st.preview.set(r.value);
  }

  async apply(): Promise<void> {
    const st = this.s();
    const p = st.preview();
    if (!p) return;
    st.busy.set(true);
    const r = await this.engine.applyDesign(this.spec().service, this.spec().table, this.design(), p.sql);
    st.busy.set(false);
    if (!r.ok) {
      st.error.set(r.error);
      return;
    }
    const name = st.name().trim();
    this.toast.emit(this.isNew() ? `Created ${name}` : `Changed ${name}`);
    await this.catalog.loadTables(this.spec().service);
    // Show the result: the table's tab (reloaded), in place of this form.
    const { service, schema } = this.spec();
    this.wb.close(this.doc().id);
    const existing = this.wb.docs().find((d) => d.spec.kind === 'table' && d.spec.service === service && d.spec.table === this.spec().table);
    if (existing) this.wb.close(existing.id);
    this.show.emit(this.wb.open({ kind: 'table', service, schema, table: name }));
  }

  async drop(): Promise<void> {
    const { service, schema, table } = this.spec();
    const typed = prompt(`Delete the table ${table} and every row in it? This can't be undone.\n\nType the table's name to confirm:`);
    if (typed === null) return;
    if (typed !== table) {
      this.s().error.set(`The name didn't match ("${typed}"): nothing was deleted.`);
      return;
    }
    const r = await this.engine.dropTable(service, schema, table, typed);
    if (!r.ok) {
      this.s().error.set(r.error);
      return;
    }
    this.toast.emit(`Deleted the table ${table}`);
    await this.catalog.loadTables(service);
    for (const d of this.wb.docs().filter((x) => x.spec.kind === 'table' && x.spec.service === service && x.spec.table === table)) this.wb.close(d.id);
    this.wb.close(this.doc().id);
  }

  // ---- editing the form ----

  setCol<K extends keyof Col>(i: number, field: K, value: Col[K]): void {
    const st = this.s();
    const before = st.columns()[i];
    st.columns.update((l) => l.map((c, j) => (j === i ? { ...c, [field]: value, ...(field === 'primaryKey' && value ? { nullable: false } : {}) } : c)));
    // Indexes and links name columns as they will be called: follow a rename.
    if (field === 'name' && before.name) {
      const rename = (cols: string[]) => cols.map((x) => (x === before.name ? (value as string) : x));
      st.indexes.update((l) => l.map((x) => ({ ...x, columns: rename(x.columns) })));
      st.links.update((l) => l.map((x) => ({ ...x, columns: rename(x.columns) })));
    }
    st.preview.set(null);
  }

  addCol(): void {
    this.s().columns.update((l) => [...l, { name: '', type: '', nullable: true, default: null, primaryKey: false }]);
    this.s().preview.set(null);
  }

  removeCol(i: number): void {
    this.s().columns.update((l) => l.filter((_, j) => j !== i));
    this.s().preview.set(null);
  }

  setIndex<K extends keyof Design['indexes'][number]>(i: number, field: K, value: Design['indexes'][number][K]): void {
    this.s().indexes.update((l) => l.map((x, j) => (j === i ? { ...x, [field]: value } : x)));
    this.s().preview.set(null);
  }

  addIndex(): void {
    const table = this.s().name().trim() || 'table';
    this.s().indexes.update((l) => [...l, { name: `${table}_idx${l.length + 1}`, columns: [], unique: false }]);
  }

  removeIndex(i: number): void {
    this.s().indexes.update((l) => l.filter((_, j) => j !== i));
    this.s().preview.set(null);
  }

  setLink<K extends keyof Design['links'][number]>(i: number, field: K, value: Design['links'][number][K]): void {
    this.s().links.update((l) =>
      l.map((x, j) => {
        if (j !== i) return x;
        const next = { ...x, [field]: value };
        // A sensible name and target column once the table is picked: "orders_user_id_fkey" → users(id).
        if (field === 'toTable' && !x.toColumns.length) next.toColumns = ['id'];
        if (!x.was && (field === 'toTable' || field === 'columns')) next.name = `${this.s().name().trim() || 'table'}_${next.columns.join('_') || 'link'}_fkey`;
        return next;
      }),
    );
    this.s().preview.set(null);
  }

  addLink(): void {
    this.s().links.update((l) => [...l, { name: '', columns: [], toSchema: this.spec().schema, toTable: '', toColumns: [] }]);
  }

  removeLink(i: number): void {
    this.s().links.update((l) => l.filter((_, j) => j !== i));
    this.s().preview.set(null);
  }

  sqlText(sql: string[]): string {
    return sql.map((x) => x + ';').join('\n\n');
  }

  split(text: string): string[] {
    return text.split(',').map((x) => x.trim()).filter(Boolean);
  }
}
