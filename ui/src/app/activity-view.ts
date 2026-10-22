import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Engine, SessionView } from './engine';
import { Doc } from './workbench';
import { Icon } from './icon';

/**
 * Other connections to one database and what each is doing, refreshed every 2 s. A stuck query can be stopped, or
 * its whole connection closed (for one "idle in transaction" that holds locks).
 */
@Component({
  selector: 'dd-activity-view',
  imports: [Icon],
  template: `
    <div class="bar">
      <span class="title"><dd-icon name="pulse" [size]="14" /> Sessions on <b class="mono">{{ service() }}</b></span>
      <label class="check"><input type="checkbox" [checked]="showIdle()" (change)="showIdle.set($any($event.target).checked)" /> show idle</label>
      <span class="grow"></span>
      <span class="dim small">{{ paused() ? 'paused' : 'refreshes every 2 s' }}</span>
      <button class="ghost sm" (click)="paused.set(!paused())"><dd-icon [name]="paused() ? 'play' : 'stop'" [size]="11" /> {{ paused() ? 'Resume' : 'Pause' }}</button>
    </div>
    @if (error(); as e) { <div class="error mono">{{ e }}</div> }
    <div class="grid">
      <table>
        <thead>
          <tr><th>pid</th><th>state</th><th>running</th><th>who</th><th>waiting for</th><th class="q">query</th><th></th></tr>
        </thead>
        <tbody>
          @for (x of shown(); track x.pid) {
            <tr [class.stuck]="x.state === 'idle in transaction' && (x.runningMs ?? 0) > 10000" [class.long]="x.state === 'active' && (x.runningMs ?? 0) > 5000">
              <td class="mono">{{ x.pid }}</td>
              <td><span class="state" [class.active]="x.state === 'active'" [class.warn]="x.state.startsWith('idle in')">{{ x.state }}</span></td>
              <td class="mono">{{ x.runningMs === null ? '' : time(x.runningMs) }}</td>
              <td class="small" [title]="x.client">{{ x.user }}@if (x.application) { <span class="dim"> · {{ x.application }}</span> }</td>
              <td class="small warn-text">{{ x.waitingFor ?? '' }}</td>
              <td class="q mono" [title]="x.query">{{ x.query }}</td>
              <td class="actions">
                @if (x.state === 'active') {
                  <button class="sm" (click)="stop(x, false)" title="Cancel the running query; the connection stays">Stop query</button>
                }
                <button class="sm ghost danger-text" (click)="stop(x, true)" title="Close the connection (its open transaction is rolled back)">Close</button>
              </td>
            </tr>
          } @empty {
            <tr><td colspan="7" class="none">No {{ showIdle() ? '' : 'busy ' }}sessions besides DevDesk's own.</td></tr>
          }
        </tbody>
      </table>
    </div>
    @if (hasStuck()) {
      <div class="explain">
        <dd-icon name="diagnosis" [size]="14" />
        <span><b>idle in transaction</b>: a program opened a transaction and stopped talking without committing. It keeps its locks, so other queries touching the same rows wait behind it. Usually a missing commit or an exception path that skips the rollback. Closing the connection rolls the transaction back.</span>
      </div>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; height: 100%; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 12px; padding: 8px 14px; border-bottom: 1px solid var(--border); }
    .title { display: inline-flex; align-items: center; gap: 6px; }
    .title dd-icon { color: var(--accent); }
    .check { display: inline-flex; align-items: center; gap: 4px; font-size: var(--fs-sm); color: var(--text-2); }
    .grow { flex: 1; }
    .dim { color: var(--text-3); }
    .small { font-size: var(--fs-sm); }
    .error { margin: 10px 14px; padding: 10px 12px; background: var(--err-bg); border: 1px solid rgba(240, 100, 90, 0.4); border-radius: var(--radius); white-space: pre-wrap; font-size: var(--fs-sm); }
    .grid { flex: 1; overflow: auto; min-height: 0; }
    table { border-collapse: separate; border-spacing: 0; width: 100%; font-size: var(--fs-sm); }
    th { position: sticky; top: 0; background: var(--bg-1); text-align: left; font-weight: 600; padding: 6px 12px; border-bottom: 1px solid var(--border-strong); white-space: nowrap; }
    td { padding: 5px 12px; border-bottom: 1px solid var(--border); white-space: nowrap; }
    td.q { max-width: 480px; overflow: hidden; text-overflow: ellipsis; color: var(--text-2); }
    .state { font-size: var(--fs-xs); border-radius: 999px; padding: 1px 8px; background: var(--bg-2); color: var(--text-2); }
    .state.active { background: var(--accent-deep); color: var(--accent); }
    .state.warn { background: var(--warn-bg); color: var(--warn); }
    tr.stuck td, tr.long td { background: var(--warn-bg); }
    .warn-text { color: var(--warn); }
    .danger-text { color: var(--err); }
    .actions { text-align: right; display: flex; gap: 6px; justify-content: flex-end; }
    .none { color: var(--text-3); padding: 16px 12px; }
    .explain { display: flex; gap: 8px; margin: 10px 14px; padding: 8px 10px; border-radius: var(--radius); background: var(--warn-bg); border: 1px solid var(--warn); font-size: var(--fs-sm); }
    .explain dd-icon { color: var(--warn); flex: none; margin-top: 2px; }
  `,
})
export class ActivityView {
  readonly engine = inject(Engine);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();
  readonly service = computed(() => (this.doc().spec as { service: string }).service);
  readonly sessions = signal<SessionView[]>([]);
  readonly error = signal<string | null>(null);
  readonly showIdle = signal(false);
  readonly paused = signal(false);
  readonly shown = computed(() => this.sessions().filter((x) => this.showIdle() || x.state !== 'idle'));
  readonly hasStuck = computed(() => this.sessions().some((x) => x.state === 'idle in transaction'));

  constructor() {
    // Inputs are set after construction: load once the tab knows its database (and again if it changes).
    effect(() => {
      this.service();
      untracked(() => this.load());
    });
    const timer = setInterval(() => !this.paused() && this.load(), 2000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  async load(): Promise<void> {
    const r = await this.engine.activity(this.service());
    this.error.set(r.ok ? null : r.error);
    if (r.ok) this.sessions.set(r.value);
  }

  async stop(x: SessionView, terminate: boolean): Promise<void> {
    if (terminate && !confirm(`Close connection ${x.pid} (${x.user}${x.application ? ', ' + x.application : ''})? Its open transaction is rolled back.`)) return;
    const r = await this.engine.stopSession(this.service(), x.pid, terminate);
    if (!r.ok) this.error.set(r.error);
    else this.toast.emit(r.value.stopped ? `${terminate ? 'Closed connection' : 'Stopped the query of'} ${x.pid}` : `Session ${x.pid} was already gone`);
    this.load();
  }

  time(ms: number): string {
    if (ms < 1000) return `${ms} ms`;
    if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
    return `${Math.floor(ms / 60_000)} min ${Math.round((ms % 60_000) / 1000)} s`;
  }
}
