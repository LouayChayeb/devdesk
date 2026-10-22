import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Engine, Startup, StartupRow } from './engine';

interface Bar {
  row: StartupRow;
  /** Segments as percentages of the chart width. */
  waiting: { left: number; width: number } | null;
  starting: { left: number; width: number } | null;
  health: { left: number; width: number } | null;
  failed: { left: number; width: number } | null;
  total: string;
  note: string;
}

/**
 * "Why does my stack take so long to start?": one bar per service, split into waiting for others, starting the
 * process and waiting for the health check, from the engine's recorded state changes. Drawing only.
 */
@Component({
  selector: 'dd-startup',
  template: `
    @if (startups().length === 0) {
      <div class="none">No startup recorded in the last 7 days. Start the project, then come back here.</div>
    } @else {
      <div class="bar">
        <select [value]="index()" (change)="index.set(+$any($event.target).value)">
          @for (s of startups(); track s.began; let i = $index) {
            <option [value]="i">{{ when(s.began) }} · {{ s.services.length }} service{{ s.services.length === 1 ? '' : 's' }} · {{ took(s) }}</option>
          }
        </select>
        <span class="legend">
          <span class="key waiting"></span> waiting for others
          <span class="key starting"></span> starting the process
          <span class="key health"></span> waiting for the health check
        </span>
      </div>
      @if (current(); as s) {
        <p class="summary">
          @if (s.tookMs !== null) {
            Ready in <b>{{ secs(s.tookMs) }}</b>.
            @if (chain().length > 1) { The slowest chain decided that: <span class="mono chain">{{ chain().join(' → ') }}</span>. }
          } @else if (failedRows().length) {
            <span class="err">{{ failedRows().join(', ') }} didn't get ready.</span> The bars show how far each one got.
          } @else {
            Still starting…
          }
        </p>
        <div class="chart">
          <div class="axis">
            <span class="name"></span>
            <span class="track">
              @for (t of ticks(); track t.left) { <span class="tick mono" [style.left.%]="t.left">{{ t.label }}</span> }
            </span>
            <span class="total"></span>
          </div>
          @for (b of bars(); track b.row.service) {
            <div class="line" [class.chain]="b.row.slowestChain" [title]="tooltip(b.row)">
              <span class="name mono">{{ b.row.service }}</span>
              <span class="track">
                @for (t of ticks(); track t.left) { <span class="grid" [style.left.%]="t.left"></span> }
                @if (b.waiting; as w) { <span class="seg waiting" [style.left.%]="w.left" [style.width.%]="w.width"></span> }
                @if (b.starting; as w) { <span class="seg starting" [style.left.%]="w.left" [style.width.%]="w.width"></span> }
                @if (b.health; as w) { <span class="seg health" [style.left.%]="w.left" [style.width.%]="w.width"></span> }
                @if (b.failed; as w) { <span class="seg failed" [style.left.%]="w.left" [style.width.%]="w.width"></span> }
              </span>
              <span class="total mono">{{ b.total }}</span>
              <span class="note small" [class.err]="b.row.outcome === 'failed'">{{ b.note }}</span>
            </div>
          }
        </div>
      }
    }
  `,
  styles: `
    :host { display: block; }
    .none { color: var(--text-2); padding: 24px 0; }
    .bar { display: flex; align-items: center; gap: 14px; margin-bottom: 8px; font-size: var(--fs-sm); color: var(--text-2); }
    select { height: 28px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 6px; }
    .legend { display: flex; align-items: center; gap: 6px; }
    .key { display: inline-block; width: 12px; height: 8px; border-radius: 2px; margin-left: 8px; }
    .summary { margin: 4px 0 14px; color: var(--text-2); }
    .summary b { color: var(--text-1); }
    .chain { color: var(--accent); }
    .err { color: var(--err); }
    .chart { display: flex; flex-direction: column; gap: 3px; }
    .axis, .line { display: grid; grid-template-columns: 120px 1fr 64px minmax(160px, 28%); align-items: center; gap: 10px; }
    .axis .track { height: 16px; }
    .track { position: relative; height: 14px; }
    .tick { position: absolute; transform: translateX(-50%); font-size: var(--fs-xs); color: var(--text-3); white-space: nowrap; }
    .tick:first-child { transform: none; }
    .tick:last-child { transform: translateX(-100%); white-space: nowrap; }
    .grid { position: absolute; top: -2px; bottom: -2px; width: 1px; background: var(--border); }
    .line { padding: 3px 0; border-radius: var(--radius-badge); }
    .line:hover { background: var(--bg-2); }
    .name { font-size: var(--fs-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--text-2); }
    .line.chain .name { color: var(--accent); }
    .seg { position: absolute; top: 2px; bottom: 2px; min-width: 2px; border-radius: 2px; }
    .seg.waiting, .key.waiting { background: repeating-linear-gradient(135deg, var(--border-strong) 0 3px, transparent 3px 6px); }
    .seg.starting, .key.starting { background: var(--accent-line); }
    .seg.health, .key.health { background: var(--accent); }
    .seg.failed { background: var(--err); }
    .line.chain .seg.starting, .line.chain .seg.health { box-shadow: 0 0 0 1px var(--accent-hover); }
    .total { text-align: right; font-size: var(--fs-sm); font-variant-numeric: tabular-nums; }
    .note { color: var(--text-3); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  `,
})
export class StartupChart implements OnInit, OnDestroy {
  readonly engine = inject(Engine);
  readonly startups = signal<Startup[]>([]);
  readonly index = signal(0);
  private timer?: ReturnType<typeof setInterval>;

  readonly current = computed(() => this.startups()[this.index()] ?? null);

  /** The chart's width in ms: the last thing that happened, at least one second. */
  private readonly span = computed(() => {
    const s = this.current();
    if (!s) return 1000;
    const ends = s.services.flatMap((r) => [r.readyMs, r.endedMs, r.runningMs, r.startingMs, r.requestedMs]);
    return Math.max(1000, ...ends.filter((x): x is number => x !== null));
  });

  readonly ticks = computed(() => {
    const span = this.span();
    return [0, 0.25, 0.5, 0.75, 1].map((f) => ({ left: f * 100, label: this.secs(f * span) }));
  });

  /** Sorted by when each one began starting: the chart then reads top to bottom like the startup did. */
  readonly bars = computed<Bar[]>(() => {
    const s = this.current();
    if (!s) return [];
    const span = this.span();
    const pct = (a: number, b: number) => ({ left: (a / span) * 100, width: (Math.max(b - a, 0) / span) * 100 });
    return [...s.services]
      .sort((a, b) => (a.startingMs ?? a.endedMs ?? Infinity) - (b.startingMs ?? b.endedMs ?? Infinity))
      .map((r) => {
        const start = r.startingMs;
        const waitEnd = start ?? r.endedMs;
        const end = r.readyMs ?? r.endedMs;
        return {
          row: r,
          waiting: waitEnd !== null && waitEnd > r.requestedMs ? pct(r.requestedMs, waitEnd) : null,
          starting: start !== null ? pct(start, r.runningMs ?? end ?? span) : null,
          health: r.runningMs !== null && r.readyMs !== null && r.readyMs > r.runningMs ? pct(r.runningMs, r.readyMs) : null,
          failed: r.outcome === 'failed' && r.endedMs !== null ? pct(r.endedMs, r.endedMs) : null,
          total: end !== null ? this.secs(end - r.requestedMs) : '…',
          note: this.note(r),
        };
      });
  });

  readonly chain = computed(() => {
    const s = this.current();
    if (!s) return [];
    return s.services
      .filter((r) => r.slowestChain && r.readyMs !== null)
      .sort((a, b) => (a.readyMs ?? 0) - (b.readyMs ?? 0))
      .map((r) => r.service);
  });

  readonly failedRows = computed(() => (this.current()?.services ?? []).filter((r) => r.outcome === 'failed').map((r) => r.service));

  ngOnInit(): void {
    this.load();
    // Faster while the newest startup is still going, so the bars grow as it happens.
    let ticks = 0;
    this.timer = setInterval(() => {
      const going = this.startups()[0]?.services.some((r) => r.outcome === 'starting') ?? false;
      if (going || ++ticks % 5 === 0) this.load(); // every 2 s while starting, else every 10 s
    }, 2000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private async load(): Promise<void> {
    const before = this.startups()[this.index()]?.began;
    const list = (await this.engine.startups()) ?? [];
    this.startups.set(list);
    // Keep showing the startup that was picked, even when a newer one is added on top.
    const keep = before ? list.findIndex((s) => s.began === before) : 0;
    this.index.set(keep >= 0 ? keep : 0);
  }

  private note(r: StartupRow): string {
    if (r.outcome === 'failed') return r.detail || 'failed';
    if (r.outcome === 'stopped') return 'stopped before it was ready';
    if (r.outcome === 'starting') return 'still starting…';
    if (r.waitedFor && r.startingMs !== null && r.startingMs > r.requestedMs) return `waited ${this.secs(r.startingMs - r.requestedMs)} for ${r.waitedFor}`;
    return '';
  }

  tooltip(r: StartupRow): string {
    const lines = [r.service];
    if (r.startingMs !== null) lines.push(`waiting for others: ${this.secs(r.startingMs - r.requestedMs)}${r.waitedFor ? ' (last: ' + r.waitedFor + ')' : ''}`);
    if (r.startingMs !== null && r.runningMs !== null) lines.push(`starting the process: ${this.secs(r.runningMs - r.startingMs)}`);
    if (r.runningMs !== null && r.readyMs !== null) lines.push(`until ready: ${this.secs(r.readyMs - r.runningMs)}`);
    if (r.dependsOn.length) lines.push(`depends on: ${r.dependsOn.join(', ')}`);
    if (r.detail) lines.push(r.detail);
    return lines.join('\n');
  }

  took(s: Startup): string {
    if (s.tookMs !== null) return this.secs(s.tookMs);
    return s.services.some((r) => r.outcome === 'failed') ? 'failed' : s.services.some((r) => r.outcome === 'starting') ? 'starting…' : 'stopped';
  }

  secs(ms: number): string {
    return ms < 10000 ? `${(ms / 1000).toFixed(1)} s` : ms < 120000 ? `${Math.round(ms / 1000)} s` : `${Math.floor(ms / 60000)} min ${Math.round((ms % 60000) / 1000)} s`;
  }

  when(iso: string): string {
    const d = new Date(iso);
    const today = new Date().toDateString() === d.toDateString();
    return (today ? '' : d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }) + ' ') + d.toLocaleTimeString('en-GB', { hour12: false });
  }
}
