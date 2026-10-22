import { Component, ElementRef, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { Engine, FileFormat, PlanView as Plan, ResultView } from './engine';
import { Catalog } from './catalog';
import { Doc, Workbench } from './workbench';
import { Icon } from './icon';
import { PlanView } from './plan-view';
import { download, savePath } from './files';

const HISTORY_MAX = 50;

/** One SQL editor tab: its text and last result stay when switching tabs. */
class SqlState {
  readonly sql = signal('');
  readonly result = signal<ResultView | null>(null);
  readonly plan = signal<Plan | null>(null);
  readonly showing = signal<'result' | 'plan'>('result');
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  /** The saved query this tab edits, if any: its id, name and the text as last saved. */
  readonly saved = signal<{ id: string; name: string; sql: string } | null>(null);
  readonly naming = signal(false);
  readonly exportOpen = signal(false);

  /** The SQL text comes back with the project's tabs; results are read again on demand. */
  snapshot(): { sql: string; saved: { id: string; name: string; sql: string } | null } {
    return { sql: this.sql(), saved: this.saved() };
  }
}

/** A read-only SQL editor on one database (the database itself refuses writes in this session). */
@Component({
  selector: 'dd-sql-view',
  imports: [Icon, PlanView],
  template: `
    @let st = s();
    <div class="editor">
      <textarea
        #box
        class="mono"
        spellcheck="false"
        [value]="st.sql()"
        (input)="onType(box)"
        (keydown)="editorKey($event)"
        placeholder="select * from orders where status = 'failed' order by created_at desc"
      ></textarea>
      @if (suggestions().length) {
        <div class="suggest mono">
          @for (x of suggestions(); track x; let i = $index) {
            <button [class.on]="i === suggestIndex()" (mousedown)="accept(x); $event.preventDefault()">{{ x }}</button>
          }
          <span class="dim small">Tab to complete</span>
        </div>
      }
      <div class="editor-bar">
        <button class="sm primary" [class.busy]="st.busy()" (click)="run()"><dd-icon name="play" [size]="11" /> Run</button>
        <kbd>Ctrl Enter</kbd>
        @if (catalog.has(service(), 'plan')) {
          <button class="sm" (click)="explain(false)" title="How the database will run this query (it is not run)"><dd-icon name="plan" [size]="12" /> Plan</button>
        }
        @if (catalog.has(service(), 'analyze')) {
          <button class="sm" (click)="explain(true)" title="Runs the query (read-only) and shows what really happened: rows and time per step"><dd-icon name="pulse" [size]="12" /> Analyze</button>
        }
        <span class="ro" title="The database itself refuses writes in this session"><dd-icon name="shield" [size]="12" /> read-only</span>
        <span class="grow"></span>
        @if (st.naming()) {
          <input #nm class="name-input" placeholder="Name this query" [value]="st.saved()?.name ?? ''" (keydown.enter)="saveQuery(nm.value)" (keydown.escape)="st.naming.set(false)" autofocus />
          <button class="sm primary" (click)="saveQuery(nm.value)">Save</button>
        } @else {
          <button class="sm ghost" (click)="st.saved() ? saveQuery(st.saved()!.name) : st.naming.set(true)" [title]="st.saved() ? 'Save changes to ' + st.saved()!.name + ' (Ctrl+S)' : 'Keep this query under the database on the left (Ctrl+S)'">
            <dd-icon name="bookmark" [size]="12" /> {{ st.saved() ? 'Save' : 'Save query' }}
          </button>
          @if (st.saved()) { <button class="sm ghost" (click)="st.naming.set(true)" title="Rename">Rename</button> }
        }
        @if (history().length) {
          <select class="sm-select history" (change)="useHistory($any($event.target).value); $any($event.target).value = ''">
            <option value="">History ({{ history().length }})</option>
            @for (h of history(); track $index) { <option [value]="h">{{ h.length > 90 ? h.slice(0, 90) + '…' : h }}</option> }
          </select>
        }
      </div>
    </div>
    @if (st.error(); as e) {
      <div class="error mono">{{ e }}</div>
    }
    @if (st.result() && st.plan()) {
      <div class="segmented switch">
        <button [class.on]="st.showing() === 'result'" (click)="st.showing.set('result')">Result</button>
        <button [class.on]="st.showing() === 'plan'" (click)="st.showing.set('plan')">Plan</button>
      </div>
    }
    @if (st.plan() && (st.showing() === 'plan' || !st.result())) {
      <dd-plan class="plan" [plan]="st.plan()!" />
    } @else if (st.result(); as r) {
      <div class="meta">
        @if (r.columns.length) { {{ r.rows.length }}{{ r.more ? '+' : '' }} rows } @else { {{ r.affected >= 0 ? r.affected + ' rows affected' : 'done' }} }
        · {{ r.ms }} ms
        <span class="grow"></span>
        @if (r.columns.length && catalog.has(service(), 'export')) {
          <span class="export">
            <button class="ghost sm" (click)="st.exportOpen.set(!st.exportOpen())" title="Every row of this query (not only the ones shown) to a file"><dd-icon name="download" [size]="12" /> Export</button>
            @if (st.exportOpen()) {
              <div class="menu">
                <button (click)="exportAs('csv')">CSV</button>
                <button (click)="exportAs('json')">JSON</button>
                <button (click)="exportAs('sql')">SQL inserts</button>
              </div>
            }
          </span>
        }
      </div>
      <div class="grid">
        <table class="mono">
          <thead>
            <tr>
              @for (c of r.columns; track $index; let i = $index) {
                <th [title]="r.types[i]">{{ c }}<span class="type">{{ r.types[i] }}</span></th>
              }
            </tr>
          </thead>
          <tbody>
            @for (row of r.rows; track $index) {
              <tr>
                @for (v of row; track $index) {
                  <td [class.null]="v === null" [title]="v ?? 'NULL'">{{ v === null ? 'NULL' : v }}</td>
                }
              </tr>
            }
          </tbody>
        </table>
        @if (r.columns.length && r.rows.length === 0) { <div class="none">No rows.</div> }
      </div>
    } @else if (!st.error()) {
      <div class="none">Type a query and press Ctrl+Enter. To change rows, open the table and edit them in the grid.</div>
    }
  `,
  host: { '(keydown)': 'hostKey($event)' },
  styles: `
    :host { display: flex; flex-direction: column; height: 100%; min-height: 0; }
    .editor { position: relative; padding: 10px 14px; border-bottom: 1px solid var(--border); flex: none; }
    textarea { width: 100%; min-height: 120px; resize: vertical; background: var(--bg-0); color: var(--text-1); border: 1px solid var(--border); border-radius: var(--radius); padding: 10px 12px; font-size: var(--fs-md); line-height: 1.5; }
    textarea:focus { outline: none; border-color: var(--accent); }
    .suggest { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; margin-top: 6px; }
    .suggest button { height: 22px; padding: 0 8px; font-size: var(--fs-sm); }
    .suggest button.on { background: var(--accent-deep); border-color: var(--accent-line); color: var(--accent); }
    .editor-bar { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
    .ro { display: inline-flex; align-items: center; gap: 5px; color: var(--accent); background: var(--accent-deep); border-radius: 999px; padding: 2px 10px; font-size: var(--fs-xs); }
    .grow { flex: 1; }
    .sm-select { height: 26px; font-size: var(--fs-sm); }
    .history { max-width: 360px; }
    .error { margin: 10px 14px; padding: 10px 12px; background: var(--err-bg); border: 1px solid rgba(240, 100, 90, 0.4); border-radius: var(--radius); white-space: pre-wrap; font-size: var(--fs-sm); }
    .meta { display: flex; align-items: center; gap: 6px; padding: 6px 14px; color: var(--text-3); font-size: var(--fs-sm); }
    .switch { margin: 8px 14px 0; align-self: flex-start; }
    .plan { flex: 1; min-height: 0; }
    .name-input { height: 26px; width: 200px; font-size: var(--fs-sm); }
    .export { position: relative; }
    .menu { position: absolute; right: 0; top: 28px; z-index: 5; display: flex; flex-direction: column; background: var(--bg-2); border: 1px solid var(--border-strong); border-radius: var(--radius); padding: 4px; min-width: 130px; }
    .menu button { justify-content: flex-start; background: transparent; border-color: transparent; height: 26px; }
    .menu button:hover { background: var(--bg-1); }
    .grid { flex: 1; overflow: auto; min-height: 0; }
    table { border-collapse: separate; border-spacing: 0; font-size: var(--fs-sm); min-width: 100%; }
    th { position: sticky; top: 0; background: var(--bg-1); text-align: left; font-weight: 600; padding: 6px 12px; border-bottom: 1px solid var(--border-strong); white-space: nowrap; }
    .type { display: block; font-weight: 400; font-size: 10px; color: var(--text-3); }
    td { padding: 4px 12px; border-bottom: 1px solid var(--border); white-space: nowrap; max-width: 360px; overflow: hidden; text-overflow: ellipsis; }
    tbody tr:hover td { background: var(--bg-1); }
    td.null { color: var(--text-3); font-style: italic; }
    .none { color: var(--text-3); padding: 16px 14px; }
    .dim { color: var(--text-3); }
    .small { font-size: var(--fs-sm); }
  `,
})
export class SqlView {
  readonly engine = inject(Engine);
  readonly catalog = inject(Catalog);
  readonly wb = inject(Workbench);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();
  readonly s = computed(() => {
    const d = this.doc();
    if (!(d.state instanceof SqlState)) {
      // untracked: filling a new state writes its signals, which a computed may not do on its own.
      d.state = untracked(() => {
        const st = new SqlState();
        const draft = d.draft as ReturnType<SqlState['snapshot']> | undefined;
        if (draft) {
          st.saved.set(draft.saved);
          st.sql.set(draft.sql);
        } else if (d.spec.kind === 'sql' && d.spec.saved) {
          st.saved.set(d.spec.saved);
          st.sql.set(d.spec.saved.sql);
        }
        return st;
      });
    }
    return d.state as SqlState;
  });
  readonly service = computed(() => (this.doc().spec as { service: string }).service);
  readonly history = signal<string[]>([]);
  readonly word = signal('');
  readonly suggestIndex = signal(0);
  private readonly box = viewChild.required<ElementRef<HTMLTextAreaElement>>('box');

  /** Words that start like the one being typed: SQL keywords, table names, and columns seen so far. */
  readonly suggestions = computed(() => {
    const w = this.word().toLowerCase();
    if (w.length < 2) return [];
    const svc = this.service();
    const words = new Set<string>([
      ...(this.catalog.tables()[svc] ?? []).map((t) => t.name),
      ...(this.catalog.columns()[svc] ?? []),
      ...['select', 'from', 'where', 'order by', 'group by', 'limit', 'join', 'left join', 'count(*)', 'distinct', 'explain'],
    ]);
    // Shortest first: "ord" → orders before order_items, so Tab picks the most likely word.
    return [...words]
      .filter((x) => x.toLowerCase().startsWith(w) && x.toLowerCase() !== w)
      .sort((a, b) => a.length - b.length || a.localeCompare(b))
      .slice(0, 8);
  });

  constructor() {
    queueMicrotask(() => {
      this.history.set(this.loadHistory());
      this.box().nativeElement.focus();
    });
    // A saved query with changes not saved yet shows a dot on its tab.
    effect(() => {
      const st = this.s();
      const saved = st.saved();
      this.doc().dirty.set(!!saved && saved.sql !== st.sql());
    });
  }

  hostKey(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
      e.preventDefault();
      const saved = this.s().saved();
      if (saved) this.saveQuery(saved.name);
      else this.s().naming.set(true);
    }
  }

  /** Plan: how the database will run it. Analyze: runs it (read-only) and measures each step. */
  async explain(analyze: boolean): Promise<void> {
    const s = this.s();
    const text = s.sql().trim();
    if (!text) return;
    s.busy.set(true);
    const r = await this.engine.plan(this.service(), text, analyze);
    s.busy.set(false);
    s.error.set(r.ok ? null : r.error);
    if (r.ok) {
      s.plan.set(r.value);
      s.showing.set('plan');
    }
  }

  async saveQuery(name: string): Promise<void> {
    const s = this.s();
    if (!name.trim() || !s.sql().trim()) return;
    const r = await this.engine.saveQuery(this.service(), { id: s.saved()?.id, name: name.trim(), sql: s.sql() });
    if (!r.ok) {
      s.error.set(r.error);
      return;
    }
    s.saved.set({ id: r.value.id, name: name.trim(), sql: s.sql() });
    s.naming.set(false);
    this.catalog.setQueries(this.service(), r.value.queries);
    const d = this.doc();
    if (d.spec.kind === 'sql') d.spec = { ...d.spec, saved: s.saved()! };
    this.wb.rename(d.id, name.trim());
    this.toast.emit(`Saved query "${name.trim()}"`);
  }

  async exportAs(format: FileFormat): Promise<void> {
    const s = this.s();
    s.exportOpen.set(false);
    const name = `${(s.saved()?.name ?? 'query').replace(/[^\w.-]+/g, '-')}.${format}`;
    const path = await savePath(name, [format]);
    if (path === null) return;
    const r = await this.engine.exportData(this.service(), { format, sql: s.sql().trim(), path: path || undefined });
    if (!r.ok) {
      s.error.set(r.error);
      return;
    }
    if (r.value.content !== undefined) download(name, r.value.content);
    this.toast.emit(`Exported ${r.value.rows.toLocaleString()} rows${r.value.path ? ' to ' + r.value.path : ''}`);
  }

  onType(el: HTMLTextAreaElement): void {
    this.s().sql.set(el.value);
    this.word.set(el.value.slice(0, el.selectionStart).match(/[\w.]+$/)?.[0] ?? '');
    this.suggestIndex.set(0);
  }

  editorKey(e: KeyboardEvent): void {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      this.run();
    } else if (e.key === 'Tab' && this.suggestions().length) {
      e.preventDefault();
      this.accept(this.suggestions()[this.suggestIndex()]);
    } else if (this.suggestions().length && (e.key === 'ArrowDown' || e.key === 'ArrowUp') && e.altKey) {
      e.preventDefault();
      const n = this.suggestions().length;
      this.suggestIndex.set((this.suggestIndex() + (e.key === 'ArrowDown' ? 1 : n - 1)) % n);
    } else if (e.key === 'Escape') {
      this.word.set('');
    }
  }

  accept(x: string): void {
    const el = this.box().nativeElement;
    const pos = el.selectionStart;
    const start = pos - this.word().length;
    const next = el.value.slice(0, start) + x + ' ' + el.value.slice(pos);
    el.value = next;
    this.s().sql.set(next);
    el.selectionStart = el.selectionEnd = start + x.length + 1;
    el.focus();
    this.word.set('');
  }

  async run(): Promise<void> {
    const s = this.s();
    const text = s.sql().trim();
    if (!text) return;
    s.busy.set(true);
    const r = await this.engine.query(this.service(), text);
    s.busy.set(false);
    s.error.set(r.ok ? null : r.error);
    s.result.set(r.ok ? r.value : null);
    s.showing.set('result');
    this.remember(text);
  }

  useHistory(text: string): void {
    if (!text) return;
    this.s().sql.set(text);
    this.box().nativeElement.value = text;
  }

  // Query history is a per-viewer convenience (like a shell's history), kept in this browser per project + database.
  private historyKey(): string {
    return `devdesk.sql.${this.engine.project()?.root}.${this.service()}`;
  }

  private loadHistory(): string[] {
    try {
      return JSON.parse(localStorage.getItem(this.historyKey()) ?? '[]');
    } catch {
      return [];
    }
  }

  private remember(text: string): void {
    const next = [text, ...this.history().filter((h) => h !== text)].slice(0, HISTORY_MAX);
    this.history.set(next);
    try {
      localStorage.setItem(this.historyKey(), JSON.stringify(next));
    } catch {
      /* not kept: fine */
    }
  }
}
