import { Component, OnDestroy, computed, effect, inject, signal, untracked } from '@angular/core';
import { Engine, TaskRun, TaskView } from './engine';
import { Icon } from './icon';

/**
 * Every service's tests in one place: pass or fail per service, the counts, which tests failed, and the output.
 * The engine finds the commands and runs them; this page only starts, stops and shows.
 */
@Component({
  selector: 'dd-tests',
  imports: [Icon],
  template: `
    <div class="head">
      <h2>Tests</h2>
      <span class="dim small">{{ summary() }}</span>
      <span class="grow"></span>
      @if (anyActive()) {
        <button class="danger" (click)="stopAll()"><dd-icon name="stop" [size]="11" /> Stop</button>
      } @else {
        <button class="primary" [disabled]="!tests().length" (click)="runAll()" title="One service after the other, so suites don't fight over ports and databases">
          <dd-icon name="play" [size]="11" /> Run all
        </button>
      }
    </div>
    @if (error(); as e) { <p class="problem small">{{ e }}</p> }

    <table>
      <thead>
        <tr><th class="st"></th><th>Service</th><th>Command</th><th>Result</th><th class="num">Time</th><th class="num">When</th><th></th></tr>
      </thead>
      <tbody>
        @for (row of rows(); track row.service) {
          @let t = row.test;
          @let r = t?.last;
          <tr [class.on]="open() === row.service" [class.none]="!t" (click)="t && toggle(row.service)">
            <td class="st"><span class="badge {{ r?.status ?? '' }}">{{ t ? icon(r?.status) : '' }}</span></td>
            <td class="mono svc">{{ row.service }}</td>
            @if (t) {
              <td class="mono cmd" [title]="t.description">
                {{ t.command }}
                @if (t.description.startsWith('detected')) { <span class="tag">detected</span> }
              </td>
              <td class="result {{ r?.status ?? '' }}">{{ result(r) }}</td>
              <td class="num mono dim">{{ r ? r.seconds.toFixed(1) + ' s' : '' }}</td>
              <td class="num mono dim">{{ r ? when(r.started) : '' }}</td>
              <td class="act" (click)="$event.stopPropagation()">
                @if (active(r)) {
                  <button class="sm ghost" (click)="stop(row.service)"><dd-icon name="stop" [size]="10" /> Stop</button>
                } @else {
                  <button class="sm" (click)="run(row.service)"><dd-icon name="play" [size]="10" /> Run</button>
                }
              </td>
            } @else {
              <td class="dim small" colspan="5">No tests found. Add <code>test: &lt;command&gt;</code> to this service in devdesk.yaml.</td>
            }
          </tr>
          @if (open() === row.service && t) {
            <tr class="detail">
              <td colspan="7">
                @if (runs()[0]; as run) {
                  @if (run.report?.failures?.length) {
                    <h4>Failed</h4>
                    <ul class="failures mono">
                      @for (f of run.report!.failures; track f) { <li>✕ {{ f }}</li> }
                    </ul>
                  }
                  <h4>Output <span class="dim plain">· {{ run.detail }}</span></h4>
                  <pre class="out">{{ (run.output ?? []).join('\\n') || '(no output yet)' }}</pre>
                } @else {
                  <p class="dim small">Not run yet. It runs <code>{{ t.command }}</code> in ./{{ t.directory }} with the service's settings.</p>
                }
                <div class="dim small">{{ t.description }}</div>
              </td>
            </tr>
          }
        }
      </tbody>
    </table>
  `,
  styles: `
    :host { display: block; padding: 16px 20px; }
    .head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
    h2 { margin: 0; font: 700 var(--fs-xl) var(--font-display); }
    .grow { flex: 1; }
    table { width: 100%; border-collapse: collapse; font-size: var(--fs-md); }
    th { text-align: left; font-weight: 500; font-size: var(--fs-xs); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.04em; padding: 6px 8px; border-bottom: 1px solid var(--border); }
    td { padding: 7px 8px; border-bottom: 1px solid var(--border); vertical-align: middle; }
    tbody tr:not(.detail):not(.none) { cursor: pointer; }
    tbody tr:not(.detail):hover { background: var(--bg-2); }
    tr.on { background: var(--accent-weak); }
    .st { width: 22px; text-align: center; }
    .svc { white-space: nowrap; }
    .cmd { color: var(--text-2); max-width: 340px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .tag { font-family: var(--font-ui); font-size: var(--fs-xs); color: var(--text-3); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 0 4px; margin-left: 6px; }
    .num { text-align: right; white-space: nowrap; }
    .act { text-align: right; width: 80px; }
    .badge { font-weight: 700; }
    .badge.ok, .result.ok { color: var(--ok); }
    .badge.failed, .badge.blocked, .result.failed, .result.blocked { color: var(--err); }
    .badge.running, .badge.waiting { color: var(--accent); }
    .detail td { background: var(--bg-1); padding: 10px 12px 14px; }
    h4 { margin: 4px 0 6px; font-size: var(--fs-xs); color: var(--text-2); text-transform: uppercase; letter-spacing: 0.04em; font-weight: 500; }
    .plain { text-transform: none; letter-spacing: 0; font-weight: 400; }
    .failures { margin: 0 0 10px; padding: 0; list-style: none; color: var(--err); font-size: var(--fs-sm); line-height: 1.6; }
    .out { max-height: 46vh; overflow: auto; margin: 0 0 8px; font: 400 var(--fs-sm) / 1.5 var(--font-mono); background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 8px 10px; white-space: pre-wrap; }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); }
    code { font-family: var(--font-mono); }
  `,
})
export class TestsPage implements OnDestroy {
  readonly engine = inject(Engine);
  readonly tests = signal<TaskView[]>([]);
  readonly open = signal<string | null>(null);
  readonly runs = signal<TaskRun[]>([]);
  readonly error = signal<string | null>(null);
  private readonly timer: ReturnType<typeof setInterval>;

  /** Every service of the project, with its tests when it has some. */
  readonly rows = computed(() => {
    const byName = new Map(this.tests().map((t) => [t.name, t]));
    return (this.engine.project()?.services ?? []).map((s) => ({ service: s.name, test: byName.get(s.name) ?? null }));
  });
  readonly anyActive = computed(() => this.tests().some((t) => this.active(t.last)));
  readonly summary = computed(() => {
    const t = this.tests();
    const passed = t.filter((x) => x.last?.status === 'ok').length;
    const failed = t.filter((x) => ['failed', 'blocked'].includes(x.last?.status ?? '')).length;
    const parts = [`${t.length} service${t.length === 1 ? '' : 's'} with tests`];
    if (passed) parts.push(`${passed} passed`);
    if (failed) parts.push(`${failed} failed`);
    return parts.join(' · ');
  });

  constructor() {
    effect(() => {
      this.engine.project();
      untracked(() => this.refresh());
    });
    this.timer = setInterval(() => {
      if (this.anyActive() || this.open()) this.refresh();
    }, 800);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  async refresh(): Promise<void> {
    if (!this.engine.project()) {
      this.tests.set([]);
      return;
    }
    const r = await this.engine.tests();
    this.tests.set(r.ok ? r.value : []);
    const open = this.open();
    if (open) {
      const runs = await this.engine.testRuns(open);
      if (runs.ok && open === this.open()) this.runs.set(runs.value);
    }
  }

  async toggle(service: string): Promise<void> {
    this.runs.set([]);
    this.open.set(this.open() === service ? null : service);
    await this.refresh();
  }

  async run(service: string): Promise<void> {
    this.error.set(null);
    const r = await this.engine.runTest(service);
    if (!r.ok) this.error.set(r.error);
    if (this.open() !== service) this.runs.set([]);
    this.open.set(service);
    await this.refresh();
  }

  async runAll(): Promise<void> {
    this.error.set(null);
    const r = await this.engine.runAllTests();
    if (!r.ok) this.error.set(r.error);
    await this.refresh();
  }

  async stop(service: string): Promise<void> {
    await this.engine.cancelTest(service);
    await this.refresh();
  }

  async stopAll(): Promise<void> {
    await this.engine.cancelAllTests();
    await this.refresh();
  }

  active(r: TaskRun | null | undefined): boolean {
    return ['running', 'waiting'].includes(r?.status ?? '');
  }

  icon(status: string | undefined): string {
    return ({ ok: '✓', failed: '✕', blocked: '✕', cancelled: '–', running: '●', waiting: '●' } as Record<string, string>)[status ?? ''] ?? '○';
  }

  /** "12 passed · 1 failed" when the tool's summary was recognised, else what the exit code says. */
  result(r: TaskRun | null | undefined): string {
    if (!r) return 'not run yet';
    if (this.active(r)) return 'running…';
    const p = r.report;
    if (p && r.status !== 'cancelled') {
      const parts = [`${p.passed} passed`];
      if (p.failed) parts.push(`${p.failed} failed`);
      if (p.skipped) parts.push(`${p.skipped} skipped`);
      return parts.join(' · ') + (p.unit === 'packages' ? ' (packages)' : '');
    }
    return { ok: 'passed', failed: `failed${r.exitCode !== null ? ' (exit ' + r.exitCode + ')' : ''}`, blocked: r.detail, cancelled: 'stopped' }[r.status] ?? r.status;
  }

  when(iso: string): string {
    return new Date(iso).toLocaleTimeString('en-GB', { hour12: false });
  }
}
