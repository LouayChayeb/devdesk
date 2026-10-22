import { Component, ElementRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Engine, FileFormat, FilterOp, ResultView, RowChangeView, StructureView } from './engine';
import { download, formatOf, openFile, savePath } from './files';
import { Catalog } from './catalog';
import { Doc, Workbench } from './workbench';
import { Icon } from './icon';
import { Autofocus } from './autofocus';
import { FakeRows } from './fake-rows';

const PAGE = 100;

export const OPS: { op: FilterOp; label: string; needsValue: boolean }[] = [
  { op: 'equals', label: '=', needsValue: true },
  { op: 'not_equals', label: '≠', needsValue: true },
  { op: 'contains', label: 'contains', needsValue: true },
  { op: 'greater', label: '>', needsValue: true },
  { op: 'less', label: '<', needsValue: true },
  { op: 'is_null', label: 'is NULL', needsValue: false },
  { op: 'is_not_null', label: 'is not NULL', needsValue: false },
];

type Cell = string | null;

/** One open table: what it shows and the edits not saved yet. Lives on the Doc so it survives switching tabs. */
class TableState {
  readonly view = signal<'rows' | 'structure'>('rows');
  readonly structure = signal<StructureView | null>(null);
  readonly result = signal<ResultView | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly orderBy = signal<string | null>(null);
  readonly desc = signal(false);
  readonly offset = signal(0);
  readonly filters = signal<{ column: string; op: FilterOp; value: string }[]>([]);
  /** Pending edits: row index on this page → column → new value. */
  readonly edits = signal<Record<number, Record<string, Cell>>>({});
  readonly deleted = signal<Set<number>>(new Set());
  readonly added = signal<Record<string, Cell>[]>([]);
  readonly saveError = signal<string | null>(null);
  readonly confirming = signal(false);
  readonly saving = signal(false);
  /** Structure and first page asked for once, when the tab is first shown. */
  loaded = false;

  readonly pending = computed(() => ({
    added: this.added().length,
    changed: Object.keys(this.edits()).filter((i) => !this.deleted().has(Number(i))).length,
    deleted: this.deleted().size,
  }));
  readonly dirty = computed(() => {
    const p = this.pending();
    return p.added + p.changed + p.deleted > 0;
  });

  discard(): void {
    this.edits.set({});
    this.deleted.set(new Set());
    this.added.set([]);
    this.saveError.set(null);
    this.confirming.set(false);
  }
}

/**
 * A table's rows and structure. Edits are held as "pending changes" until Save, which sends them to the engine's
 * separate write path in one transaction. Deleting asks first.
 */
@Component({
  selector: 'dd-table-view',
  imports: [Icon, Autofocus, FakeRows],
  templateUrl: './table-view.html',
  styleUrl: './table-view.css',
  // On the document, not the host: finishing an edit with Enter removes the input, and focus falls back to <body>.
  host: { '(document:keydown)': 'key($event)' },
})
export class TableView {
  readonly engine = inject(Engine);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly catalog = inject(Catalog);
  readonly wb = inject(Workbench);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();
  /** "Change table…" opens the table form as its own tab. */
  readonly show = output<Doc>();
  readonly ops = OPS;

  readonly s = computed(() => {
    const d = this.doc();
    if (!(d.state instanceof TableState)) d.state = new TableState();
    return d.state as TableState;
  });
  readonly spec = computed(() => {
    const sp = this.doc().spec;
    if (sp.kind !== 'table') throw new Error('not a table doc');
    return sp;
  });
  readonly fileMenu = signal<'export' | null>(null);
  /** The "Test rows" window. */
  readonly fakeOpen = signal(false);
  /** The cell being typed in: row (-1 - n for new row n), column. */
  readonly editing = signal<{ row: number; col: string } | null>(null);

  readonly primaryKey = computed(() => this.s().structure()?.columns.filter((c) => c.primaryKey).map((c) => c.name) ?? []);
  readonly editable = computed(() => this.primaryKey().length > 0);
  readonly isView = computed(
    () => !!this.catalog.tables()[this.spec().service]?.find((t) => t.schema === this.spec().schema && t.name === this.spec().table)?.view,
  );

  constructor() {
    // The tab shows a dot while there are unsaved changes, and asks before closing.
    effect(() => this.doc().dirty.set(this.s().dirty()));
    effect(() => {
      const s = this.s();
      if (!s.loaded) {
        s.loaded = true;
        untracked(() => this.load(true));
      }
    });
  }

  changeTable(): void {
    const { service, schema, table } = this.spec();
    this.show.emit(this.wb.open({ kind: 'design', service, schema, table }));
  }

  async load(first = false): Promise<void> {
    const s = this.s();
    const { service, schema, table, filter } = this.spec();
    if (first) {
      if (filter) s.filters.set([{ column: filter.column, op: 'equals', value: filter.value }]);
      const st = await this.engine.structure(service, schema, table);
      s.structure.set(st.ok ? st.value : null);
      if (st.ok) this.catalog.rememberColumns(service, st.value.columns.map((c) => c.name));
      else s.error.set(st.error);
    }
    s.busy.set(true);
    const r = await this.engine.rows(service, {
      schema,
      table,
      orderBy: s.orderBy() ?? undefined,
      descending: s.desc(),
      filters: s.filters(),
      offset: s.offset(),
      limit: PAGE,
    });
    s.busy.set(false);
    s.error.set(r.ok ? null : r.error);
    s.result.set(r.ok ? r.value : null);
  }

  sortBy(column: string): void {
    const s = this.s();
    if (s.dirty()) return;
    if (s.orderBy() === column) s.desc.set(!s.desc());
    else {
      s.orderBy.set(column);
      s.desc.set(false);
    }
    s.offset.set(0);
    this.load();
  }

  page(dir: number): void {
    const s = this.s();
    s.offset.set(Math.max(0, s.offset() + dir * PAGE));
    this.load();
  }

  addFilter(column: string, op: FilterOp, value: string): void {
    if (!column || (this.needsValue(op) && value === '')) return;
    this.s().filters.update((f) => [...f, { column, op, value }]);
    this.s().offset.set(0);
    this.load();
  }

  removeFilter(i: number): void {
    this.s().filters.update((f) => f.filter((_, j) => j !== i));
    this.s().offset.set(0);
    this.load();
  }

  opLabel = (op: FilterOp) => OPS.find((o) => o.op === op)?.label ?? op;
  needsValue = (op: FilterOp) => OPS.find((o) => o.op === op)?.needsValue ?? true;
  isPk = (c: string) => this.primaryKey().includes(c);
  type = (c: string) => this.s().structure()?.columns.find((x) => x.name === c)?.type ?? '';

  /** A column that points at another table: its values become links that open that table, filtered. */
  link(column: string) {
    return this.s().structure()?.foreignKeys.find((f) => f.columns.length === 1 && f.columns[0] === column) ?? null;
  }

  follow(fk: StructureView['foreignKeys'][number], value: string): void {
    this.wb.open({ kind: 'table', service: this.spec().service, schema: fk.toSchema, table: fk.toTable, filter: { column: fk.toColumns[0], value } });
  }

  openTable(schema: string, table: string): void {
    this.wb.open({ kind: 'table', service: this.spec().service, schema, table });
  }

  // ---- files ----

  /** Every row matching the current filters and sort (not only this page), written by the engine. */
  async exportAs(format: FileFormat): Promise<void> {
    this.fileMenu.set(null);
    const s = this.s();
    const { service, schema, table } = this.spec();
    const name = `${table}.${format}`;
    const path = await savePath(name, [format]);
    if (path === null) return;
    const r = await this.engine.exportData(service, {
      format,
      schema,
      table,
      orderBy: s.orderBy() ?? undefined,
      descending: s.desc(),
      filters: s.filters(),
      path: path || undefined,
    });
    if (!r.ok) {
      s.error.set(r.error);
      return;
    }
    if (r.value.content !== undefined) download(name, r.value.content);
    this.toast.emit(`Exported ${r.value.rows.toLocaleString()} rows of ${table}${r.value.path ? ' to ' + r.value.path : ''}`);
  }

  /** CSV and JSON rows are inserted into this table; a SQL file runs as a script. One transaction either way. */
  async importFile(): Promise<void> {
    const s = this.s();
    const file = await openFile(['csv', 'json', 'sql']);
    if (!file) return;
    const name = 'path' in file ? file.path : file.name;
    const format = formatOf(name);
    if (!format) {
      s.error.set(`${name}: import takes .csv, .json or .sql files`);
      return;
    }
    const { service, schema, table } = this.spec();
    const target = format === 'sql' ? 'the database' : table;
    if (!confirm(`Import ${name.split('/').pop()} into ${target}? It runs in one transaction: all rows or none.`)) return;
    s.busy.set(true);
    const r = await this.engine.importData(service, { format, schema, table, ...file });
    s.busy.set(false);
    if (!r.ok) {
      s.error.set(r.error);
      return;
    }
    this.toast.emit(`Imported: ${r.value.rows.toLocaleString()} rows (${r.value.ms} ms)`);
    await this.load();
    this.catalog.loadTables(service);
  }

  // ---- editing ----

  /** What a cell shows: the pending value if there is one. */
  value(row: number, colIndex: number, original: Cell): Cell {
    const col = this.s().result()!.columns[colIndex];
    const e = this.s().edits()[row];
    return e && col in e ? e[col] : original;
  }

  changed(row: number, col: string): boolean {
    const e = this.s().edits()[row];
    return !!e && col in e;
  }

  startEdit(row: number, col: string): void {
    if (row >= 0 && (!this.editable() || this.s().deleted().has(row))) return;
    this.editing.set({ row, col });
  }

  /** Keeps a typed value as a pending change (nothing is sent yet). */
  commit(row: number, col: string, value: Cell, original: Cell): void {
    const ed = this.editing();
    if (!ed || ed.row !== row || ed.col !== col) return; // already handled (Enter or Escape, then blur)
    this.editing.set(null);
    const s = this.s();
    if (row < 0) {
      const n = -1 - row;
      if (value === '' && !(col in s.added()[n])) return; // left empty: the column keeps its default
      s.added.update((a) => a.map((r, i) => (i === n ? { ...r, [col]: value } : r)));
      return;
    }
    s.edits.update((all) => {
      const next = { ...all, [row]: { ...(all[row] ?? {}) } };
      if (value === original) delete next[row][col];
      else next[row][col] = value;
      if (Object.keys(next[row]).length === 0) delete next[row];
      return next;
    });
    s.saveError.set(null);
  }

  toggleDelete(row: number): void {
    const s = this.s();
    s.deleted.update((d) => {
      const next = new Set(d);
      next.has(row) ? next.delete(row) : next.add(row);
      return next;
    });
    s.confirming.set(false);
  }

  addRow(): void {
    this.s().added.update((a) => [...a, {}]);
    const first = this.s().structure()?.columns.find((c) => !c.default) ?? this.s().structure()?.columns[0];
    if (first) this.editing.set({ row: -this.s().added().length, col: first.name });
  }

  removeAdded(n: number): void {
    this.s().added.update((a) => a.filter((_, i) => i !== n));
  }

  /** Save: deletes are confirmed first, because they can't be undone. */
  save(): void {
    const s = this.s();
    if (!s.dirty() || s.saving()) return;
    if (s.deleted().size > 0 && !s.confirming()) {
      s.confirming.set(true);
      return;
    }
    this.send();
  }

  private async send(): Promise<void> {
    const s = this.s();
    const r = s.result()!;
    const pk = this.primaryKey();
    const keyOf = (row: number) => Object.fromEntries(pk.map((c) => [c, r.rows[row][r.columns.indexOf(c)] ?? '']));
    const changes: RowChangeView[] = [
      ...[...s.deleted()].map((row): RowChangeView => ({ op: 'delete', key: keyOf(row) })),
      ...Object.entries(s.edits())
        .filter(([row]) => !s.deleted().has(Number(row)))
        .map(([row, values]): RowChangeView => ({ op: 'update', key: keyOf(Number(row)), values })),
      ...s.added().map((values): RowChangeView => ({ op: 'insert', values })),
    ];
    s.saving.set(true);
    s.confirming.set(false);
    const { service, schema, table } = this.spec();
    const res = await this.engine.saveChanges(service, schema, table, changes);
    s.saving.set(false);
    if (!res.ok) {
      s.saveError.set(res.error);
      return;
    }
    s.discard();
    this.toast.emit(`Saved: ${res.value.rows} row${res.value.rows === 1 ? '' : 's'} changed in ${table} (${res.value.ms} ms)`);
    await this.load();
    this.catalog.loadTables(service); // row counts changed
  }

  key(e: KeyboardEvent): void {
    if (!(e.ctrlKey || e.metaKey) || e.key.toLowerCase() !== 's' || e.defaultPrevented) return;
    const el = this.host.nativeElement;
    const focus = document.activeElement;
    const mine = focus && focus !== document.body ? el.contains(focus) : el.offsetParent !== null; // hidden tabs ignore it
    if (mine && this.s().dirty()) {
      e.preventDefault();
      this.save();
    }
  }

  inputKey(e: KeyboardEvent, row: number, col: string, el: HTMLInputElement, original: Cell): void {
    if (e.key === 'Enter') this.commit(row, col, el.value, original);
    else if (e.key === 'Escape') {
      e.stopPropagation();
      this.editing.set(null);
    }
  }
}
