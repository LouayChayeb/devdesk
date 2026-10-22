import { Component, OnDestroy, OnInit, computed, inject, input, signal } from '@angular/core';
import { Engine, StressView } from './engine';
import { Doc } from './workbench';
import { Icon } from './icon';

/**
 * The stress lab: more and more callers send one request to a service of this machine; the graph shows requests,
 * errors and slowness per second, and under it each service's state and CPU, so you see which one gave up first.
 * The engine sends the load and writes the verdict; this draws it.
 */
@Component({
  selector: 'dd-stress-view',
  imports: [Icon],
  template: `
    <div class="form">
      <select [value]="method()" (change)="method.set($any($event.target).value)">
        @for (m of methods; track m) { <option [value]="m">{{ m }}</option> }
      </select>
      <input class="mono url" placeholder="http://localhost:3000/orders" [value]="url()" (input)="url.set($any($event.target).value)" />
      <label class="small">callers up to <input class="num" type="number" min="1" max="500" [value]="max()" (input)="max.set(+$any($event.target).value)" /></label>
      <label class="small">for <input class="num" type="number" min="5" max="300" [value]="seconds()" (input)="seconds.set(+$any($event.target).value)" /> s</label>
      @if (view()?.running) {
        <button class="danger sm" (click)="stop()"><dd-icon name="stop" [size]="10" /> Stop</button>
      } @else {
        <button class="primary sm" [disabled]="!url().trim()" (click)="start()"><dd-icon name="play" [size]="11" /> Start</button>
      }
    </div>
    @if (method() !== 'GET') {
      <textarea class="mono" rows="3" placeholder='request body, e.g. {"item": "lamp"}' [value]="body()" (input)="body.set($any($event.target).value)"></textarea>
    }
    <p class="dim small">Only this machine is ever loaded (localhost, 127.0.0.1). The callers climb from 1 to the maximum, so the first seconds show normal speed.</p>
    @if (error(); as e) { <p class="problem small">{{ e }}</p> }

    @if (view(); as v) {
      @if (v.verdict; as verdict) {
        <div class="verdict" [class.bad]="verdict.breakingSecond !== null">{{ verdict.text }}</div>
      }
      @if (v.seconds.length) {
        <div class="legend small">
          <span class="k req"></span> requests/s (max {{ peak().requests }})
          <span class="k err"></span> errors/s
          <span class="k p95"></span> 95% answered within (max {{ peak().p95 }} ms)
        </div>
        <svg class="chart" viewBox="0 0 1000 160" preserveAspectRatio="none">
          @if (breakX() !== null) { <line class="break" [attr.x1]="breakX()" [attr.x2]="breakX()" y1="0" y2="160" /> }
          <polyline class="req" [attr.points]="line('requests')" />
          <polyline class="err" [attr.points]="line('errors')" />
          <polyline class="p95" [attr.points]="line('p95')" />
        </svg>
        <div class="lanes">
          @for (s of lanes(); track s.service) {
            <div class="lane">
              <span class="mono name" [class.first]="s.service === v.verdict?.firstToGiveUp">{{ s.service }}</span>
              <span class="cells">
                @for (c of s.cells; track c.second) {
                  <span class="cell {{ c.label }}" [style.opacity]="0.35 + Math.min(c.cpu, 100) / 155" [title]="'second ' + (c.second + 1) + ': ' + c.label + ', CPU ' + c.cpu + '%'"></span>
                }
              </span>
            </div>
          }
        </div>
        <div class="dim small">Each square is one second: the colour is the service's state, darker means more CPU.</div>
      }
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 8px; padding: 10px 14px; overflow: auto; }
    .form { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
    select, input, textarea { background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 8px; height: 28px; }
    textarea { height: auto; padding: 6px 8px; }
    .url { flex: 1; min-width: 260px; }
    .num { width: 70px; margin: 0 4px; }
    .verdict { background: var(--ok-bg); border-left: 2px solid var(--ok); padding: 8px 12px; border-radius: var(--radius-badge); }
    .verdict.bad { background: var(--warn-bg); border-left-color: var(--warn); }
    .legend { display: flex; gap: 6px; align-items: center; color: var(--text-2); }
    .k { display: inline-block; width: 14px; height: 3px; margin-left: 10px; }
    .k.req, polyline.req { background: var(--accent); stroke: var(--accent); }
    .k.err, polyline.err { background: var(--err); stroke: var(--err); }
    .k.p95, polyline.p95 { background: var(--warn); stroke: var(--warn); }
    .chart { width: 100%; height: 160px; background: var(--bg-1); border: 1px solid var(--border); border-radius: var(--radius); }
    polyline { fill: none; stroke-width: 2; vector-effect: non-scaling-stroke; }
    line.break { stroke: var(--err); stroke-dasharray: 4 4; vector-effect: non-scaling-stroke; }
    .lanes { display: flex; flex-direction: column; gap: 3px; }
    .lane { display: flex; align-items: center; gap: 8px; }
    .name { width: 110px; flex: none; font-size: var(--fs-sm); overflow: hidden; text-overflow: ellipsis; color: var(--text-2); }
    .name.first { color: var(--err); font-weight: 600; }
    .cells { flex: 1; display: flex; gap: 1px; }
    .cell { flex: 1; height: 12px; border-radius: 1px; background: var(--ok); }
    .cell.running { background: var(--accent); }
    .cell.unhealthy, .cell.crashed, .cell.failed, .cell.crash-loop { background: var(--err); opacity: 1 !important; }
    .cell.stopped, .cell.stopping { background: var(--idle); }
    .cell.starting, .cell.waiting { background: var(--warn); }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); margin: 0; }
  `,
})
export class StressViewComponent implements OnInit, OnDestroy {
  readonly engine = inject(Engine);
  readonly doc = input.required<Doc>();
  readonly Math = Math;
  readonly methods = ['GET', 'POST', 'PUT', 'DELETE'];
  readonly method = signal('GET');
  readonly url = signal('');
  readonly body = signal('');
  readonly max = signal(50);
  readonly seconds = signal(60);
  readonly view = signal<StressView | null>(null);
  readonly error = signal<string | null>(null);
  private timer?: ReturnType<typeof setInterval>;

  readonly peak = computed(() => {
    const s = this.view()?.seconds ?? [];
    return { requests: Math.max(1, ...s.map((x) => x.requests)), p95: Math.round(Math.max(1, ...s.map((x) => x.p95))) };
  });

  readonly breakX = computed(() => {
    const v = this.view();
    const b = v?.verdict?.breakingSecond;
    return b === null || b === undefined || !v?.plan ? null : ((b + 0.5) / v.plan.seconds) * 1000;
  });

  readonly lanes = computed(() =>
    Object.entries(this.view()?.services ?? {}).map(([service, cells]) => ({ service, cells })),
  );

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => {
      if (this.view()?.running) this.load();
    }, 1000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  async load(): Promise<void> {
    const r = await this.engine.stress();
    if (r.ok) {
      this.view.set(r.value);
      if (r.value.plan && !this.url()) this.url.set(r.value.plan.url);
    }
  }

  async start(): Promise<void> {
    this.error.set(null);
    const r = await this.engine.startStress({
      method: this.method(),
      url: this.url().trim(),
      body: this.body(),
      headers: this.body().trim().startsWith('{') ? { 'Content-Type': 'application/json' } : {},
      maxConcurrency: this.max(),
      seconds: this.seconds(),
    });
    if (r.ok) this.view.set(r.value);
    else this.error.set(r.error);
  }

  async stop(): Promise<void> {
    const r = await this.engine.stopStress();
    if (r.ok) this.view.set(r.value);
  }

  /** A series as SVG points: x spread over the planned seconds, y scaled to its own peak (each line its own scale). */
  line(key: 'requests' | 'errors' | 'p95'): string {
    const v = this.view();
    if (!v?.plan) return '';
    const s = v.seconds;
    const peak = key === 'p95' ? this.peak().p95 : this.peak().requests;
    return s
      .map((x) => `${(((x.second + 0.5) / v.plan!.seconds) * 1000).toFixed(1)},${(155 - (x[key] / peak) * 150).toFixed(1)}`)
      .join(' ');
  }
}
