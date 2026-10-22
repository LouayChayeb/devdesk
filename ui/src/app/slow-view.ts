import { Component, OnDestroy, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { Engine, SlowQueriesView, SlowStatement } from './engine';
import { Doc } from './workbench';
import { Icon } from './icon';

type Sort = 'totalMs' | 'meanMs' | 'calls' | 'maxMs';

/**
 * Postgres's own list of its slowest queries (pg_stat_statements), with the steps to switch it on. Opened from the
 * Data page, or from a slow API request with that request's query highlighted. The engine reads and changes; this
 * shows, sorts and asks.
 */
@Component({
  selector: 'dd-slow-view',
  imports: [Icon],
  template: `
    @let svc = service();
    <div class="bar">
      <h3>Slowest queries</h3>
      <span class="dim small">{{ svc }} · from Postgres's own counters (pg_stat_statements)@if (data()?.since; as s) {, since {{ when(s) }}}</span>
      <span class="grow"></span>
      @if (data()?.status === 'ready') {
        <span class="segmented small">
          @for (s of sorts; track s.key) { <button [class.on]="sort() === s.key" (click)="sort.set(s.key)">{{ s.label }}</button> }
        </span>
        <button class="ghost sm" (click)="reset()" title="Start counting from zero: then do the slow thing in the app and look again"><dd-icon name="undo" [size]="12" /> Reset</button>
      }
      <button class="ghost icon sm" title="Reload" (click)="load()"><dd-icon name="restart" [size]="13" /></button>
    </div>
    @if (error(); as e) { <p class="problem small">{{ e }}</p> }

    @switch (data()?.status) {
      @case ('not_loaded') {
        <div class="setup">
          <p>Postgres keeps this list only with its <b>pg_stat_statements</b> module, which is loaded when the server starts. It isn't loaded on {{ svc }}.</p>
          @if (data()!.load === 'restart') {
            <p class="dim small">DevDesk can switch it on: it changes a server setting, then restarts {{ svc }} (a few seconds; open connections are closed). Your data stays: it's on a volume.</p>
            <button class="primary" [disabled]="busy()" (click)="step('load')"><dd-icon name="restart" [size]="12" /> Load it and restart {{ svc }}</button>
          } @else {
            <p class="dim small">{{ svc }} keeps no data between starts, so a setting changed now would be lost at the restart. Put Postgres's own switch in devdesk.yaml, then restart {{ svc }}:</p>
            <pre class="mono yaml">{{ data()!.yaml }}</pre>
            <button class="sm" (click)="copy(data()!.yaml!)"><dd-icon name="copy" [size]="12" /> {{ copied() ? 'Copied' : 'Copy' }}</button>
          }
        </div>
      }
      @case ('not_created') {
        <div class="setup">
          <p>The module is loaded. One step left: create the extension in this database.</p>
          <button class="primary" [disabled]="busy()" (click)="step('create')"><dd-icon name="play" [size]="11" /> Turn it on</button>
        </div>
      }
      @case ('ready') {
        @if (sorted().length === 0) {
          <p class="dim">Nothing counted yet. Use the app, then reload.</p>
        } @else {
          <table class="mono">
            <thead><tr><th class="n">#</th><th>Query</th><th class="n">Calls</th><th class="n">Total</th><th class="n">Average</th><th class="n">Slowest</th><th class="n">Rows</th></tr></thead>
            <tbody>
              @for (s of sorted(); track s.shape; let i = $index) {
                <tr [class.hl]="s.shape === highlight()" [class.open]="opened() === s.shape" (click)="opened.set(opened() === s.shape ? null : s.shape)">
                  <td class="n dim">{{ i + 1 }}</td>
                  <td class="q">{{ s.query }}</td>
                  <td class="n">{{ s.calls.toLocaleString('en-US') }}</td>
                  <td class="n">{{ ms(s.totalMs) }}</td>
                  <td class="n" [class.slow]="s.meanMs > 100">{{ ms(s.meanMs) }}</td>
                  <td class="n">{{ ms(s.maxMs) }}</td>
                  <td class="n dim">{{ s.rows.toLocaleString('en-US') }}</td>
                </tr>
              }
            </tbody>
          </table>
          <p class="dim small">Values are $1, $2: one line counts every run of the same query. Click a line to see all of it.</p>
        }
      }
      @default { <p class="dim">Reading…</p> }
    }
  `,
  styles: `
    :host { display: block; padding: 12px 16px; overflow: auto; }
    .bar { display: flex; align-items: baseline; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
    h3 { margin: 0; font: 700 var(--fs-lg) var(--font-display); }
    .grow { flex: 1; }
    .setup { max-width: 720px; display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
    .setup p { margin: 0; }
    .yaml { background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 8px 12px; margin: 0; align-self: stretch; }
    table { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
    th { text-align: left; font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.04em; padding: 4px 8px; border-bottom: 1px solid var(--border); }
    td { padding: 5px 8px; border-bottom: 1px solid var(--border); vertical-align: top; }
    tbody tr { cursor: pointer; }
    tbody tr:hover { background: var(--bg-2); }
    tr.hl { background: var(--accent-weak); box-shadow: inset 2px 0 0 var(--accent); }
    .q { max-width: 0; width: 60%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-1); }
    tr.open .q { white-space: pre-wrap; word-break: break-word; }
    .n { text-align: right; white-space: nowrap; font-variant-numeric: tabular-nums; }
    .slow { color: var(--warn); }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); }
  `,
})
export class SlowView implements OnInit, OnDestroy {
  readonly engine = inject(Engine);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();

  readonly data = signal<SlowQueriesView | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly sort = signal<Sort>('totalMs');
  readonly opened = signal<string | null>(null);
  readonly copied = signal(false);
  readonly sorts: { key: Sort; label: string }[] = [
    { key: 'totalMs', label: 'Total time' },
    { key: 'meanMs', label: 'Average' },
    { key: 'maxMs', label: 'Slowest' },
    { key: 'calls', label: 'Calls' },
  ];
  private timer?: ReturnType<typeof setInterval>;

  readonly service = computed(() => {
    const s = this.doc().spec;
    return s.kind === 'slow' ? s.service : '';
  });
  /** The shape of the query a slow request ran, when opened from that request. */
  readonly highlight = computed(() => {
    const s = this.doc().spec;
    return s.kind === 'slow' ? (s.highlight ?? null) : null;
  });
  readonly sorted = computed<SlowStatement[]>(() => {
    const key = this.sort();
    return [...(this.data()?.statements ?? [])].sort((a, b) => b[key] - a[key]);
  });

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 10000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  async load(): Promise<void> {
    const r = await this.engine.slowQueries(this.service());
    if (r.ok) {
      this.data.set(r.value);
      this.error.set(null);
    } else {
      this.error.set(r.error);
    }
  }

  async step(step: 'load' | 'create' | 'reset'): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    const r = await this.engine.slowQueriesStep(this.service(), step);
    if (!r.ok) {
      this.error.set(r.error);
      this.busy.set(false);
      return;
    }
    if (step === 'load') {
      this.toast.emit(`Restarting ${this.service()} with pg_stat_statements…`);
      // Wait for the database to be back before asking it again.
      for (let i = 0; i < 30; i++) {
        await new Promise((ok) => setTimeout(ok, 2000));
        const again = await this.engine.slowQueries(this.service());
        if (again.ok && again.value.status !== 'not_loaded') break;
      }
    }
    this.busy.set(false);
    await this.load();
  }

  async reset(): Promise<void> {
    if (!confirm(`Start counting from zero on ${this.service()}? The current numbers are dropped.`)) return;
    await this.step('reset');
  }

  async copy(text: string): Promise<void> {
    await navigator.clipboard.writeText(text);
    this.copied.set(true);
    setTimeout(() => this.copied.set(false), 1500);
  }

  ms(v: number): string {
    return v < 1 ? `${v.toFixed(2)} ms` : v < 1000 ? `${v.toFixed(1)} ms` : `${(v / 1000).toFixed(2)} s`;
  }

  when(iso: string): string {
    const d = new Date(iso);
    return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }) + ' ' + d.toLocaleTimeString('en-GB', { hour12: false });
  }
}
