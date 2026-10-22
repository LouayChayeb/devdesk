import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Engine, History, HistoryItem } from './engine';
import { bytes, time } from './format';
import { StartupChart } from './startup-chart';

/** The flight recorder's timeline (ADR 0008), like `devdesk history`. The engine decides what's routine or unclean. */
@Component({
  selector: 'dd-timeline',
  imports: [StartupChart],
  template: `
    <div class="views segmented">
      <button [class.on]="view() === 'events'" (click)="view.set('events')">Events</button>
      <button [class.on]="view() === 'startup'" (click)="view.set('startup')" title="How long each service took to start, and what it waited for">Startup</button>
    </div>
    @if (view() === 'startup') {
      <dd-startup />
    } @else {
    <div class="bar">
      <div class="segmented">
        @for (h of ranges; track h.hours) {
          <button [class.on]="hours() === h.hours" (click)="setHours(h.hours)">{{ h.label }}</button>
        }
      </div>
      <label><input type="checkbox" [checked]="showRoutine()" (change)="showRoutine.set(!showRoutine())" /> routine states</label>
      <span class="grow"></span>
      @if (history(); as h) {
        <span class="folder mono" [title]="h.folder">{{ h.items.length }} entries</span>
      }
    </div>
    @if (memory().length) {
      <div class="mem">
        @for (m of memory(); track m.service) {
          <div class="spark">
            <span class="mono svc">{{ m.service }}</span>
            <svg viewBox="0 0 100 20" preserveAspectRatio="none"><polyline [attr.points]="m.points" /></svg>
            <span class="mono num">{{ bytes(m.last) }} <span class="dim">peak {{ bytes(m.peak) }}</span></span>
          </div>
        }
      </div>
    }
    @if (rows().length === 0) {
      <div class="none">Nothing recorded yet. DevDesk records while the app or <span class="mono">devdesk up</span> runs this project.</div>
    }
    <div class="list mono">
      @for (r of rows(); track $index) {
        @if (r.afterUncleanStop) {
          <div class="row unclean"><span class="t"></span><span class="sym">!!</span>No clean stop was recorded before this: DevDesk was killed or crashed, and its services may have kept running.</div>
        }
        <div class="row {{ r.kind }} {{ r.data['to'] ?? '' }}">
          <span class="t">{{ time(r.at) }}</span>
          <span class="svc">{{ r.service }}</span>
          <span class="sym">{{ symbol(r) }}</span>
          <span class="text">{{ r.summary }}</span>
        </div>
      }
    </div>
    }
  `,
  styles: `
    :host { display: block; padding: 12px 16px; }
    .views { margin-bottom: 12px; }
    .bar { display: flex; align-items: center; gap: 6px; margin-bottom: 10px; color: var(--text-2); font-size: var(--fs-sm); }
    .bar label { margin-left: 8px; display: flex; gap: 4px; align-items: center; }
    .grow { flex: 1; }
    .folder { color: var(--text-3); }
    .mem { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 6px; margin-bottom: 12px; }
    .spark { display: flex; align-items: center; gap: 8px; background: var(--bg-1); border: 1px solid var(--border); border-radius: var(--radius); padding: 4px 8px; }
    .spark .svc { width: 70px; overflow: hidden; text-overflow: ellipsis; font-size: var(--fs-sm); }
    .spark svg { flex: 1; height: 20px; }
    .spark polyline { fill: none; stroke: var(--accent); stroke-width: 1.2; vector-effect: non-scaling-stroke; }
    .spark .num { font-size: var(--fs-sm); font-variant-numeric: tabular-nums; }
    .dim { color: var(--text-3); }
    .none { color: var(--text-2); padding: 24px 0; }
    .list { font-size: var(--fs-sm); line-height: 1.7; }
    .row { display: flex; gap: 10px; }
    .t { color: var(--text-3); width: 64px; flex: none; font-variant-numeric: tabular-nums; }
    .svc { width: 80px; flex: none; color: var(--text-2); overflow: hidden; text-overflow: ellipsis; }
    .sym { width: 16px; flex: none; text-align: center; }
    .text { flex: 1; word-break: break-word; }
    .session { color: var(--text-2); border-top: 1px solid var(--border); margin-top: 4px; padding-top: 2px; }
    .finding .text { color: var(--text-2); padding-left: 12px; }
    .healthy .sym, .running .sym { color: var(--ok); }
    .failed .sym, .crashed .sym, .crash-loop .sym, .unhealthy .sym { color: var(--err); }
    .blocked .sym, .warning .sym { color: var(--warn); }
    .unclean { color: var(--err); }
  `,
})
export class TimelinePanel implements OnInit, OnDestroy {
  readonly engine = inject(Engine);
  readonly time = time;
  readonly bytes = bytes;
  readonly ranges = [
    { hours: 1, label: '1h' },
    { hours: 24, label: '24h' },
    { hours: 24 * 7, label: '7d' },
  ];
  readonly hours = signal(24);
  /** Events: the flight recorder's list. Startup: the chart of the last startups. */
  readonly view = signal<'events' | 'startup'>('events');
  readonly showRoutine = signal(false);
  readonly history = signal<History | null>(null);
  private timer?: ReturnType<typeof setInterval>;

  readonly rows = computed<HistoryItem[]>(() =>
    (this.history()?.items ?? []).filter((i) => i.kind !== 'resources' && (this.showRoutine() || !i.routine)).reverse(),
  );

  /** One small memory line per service from the recorded samples (every 10 s). Drawing only. */
  readonly memory = computed(() => {
    const per = new Map<string, number[]>();
    for (const i of this.history()?.items ?? []) {
      if (i.kind === 'resources') per.set(i.service, [...(per.get(i.service) ?? []), Number(i.data['memory'] ?? 0)]);
    }
    return [...per.entries()].map(([service, values]) => {
      const peak = Math.max(...values, 1);
      const step = values.length > 1 ? 100 / (values.length - 1) : 100;
      const points = values.map((v, i) => `${(i * step).toFixed(1)},${(20 - (v / peak) * 18).toFixed(1)}`).join(' ');
      return { service, points, peak, last: values[values.length - 1] };
    });
  });

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 5000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  setHours(h: number): void {
    this.hours.set(h);
    this.load();
  }

  symbol(i: HistoryItem): string {
    switch (i.kind) {
      case 'session':
        return '──';
      case 'finding':
        return '↳';
      case 'warning':
        return '!';
      default:
        return (
          ({ healthy: '✓', running: '▶', starting: '…', failed: '✗', crashed: '✗', 'crash-loop': '✗', unhealthy: '✗', blocked: '✗', stopped: '■' } as Record<string, string>)[
            i.data['to']
          ] ?? '·'
        );
    }
  }

  private async load(): Promise<void> {
    this.history.set(await this.engine.history(this.hours()));
  }
}
