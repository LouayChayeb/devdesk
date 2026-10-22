import { Component, OnDestroy, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { Engine, RecordedCall, RecordingView, ReplayResult } from './engine';
import { Doc } from './workbench';
import { Icon } from './icon';

/**
 * The traffic recorder: pick a service, point callers at the recording port, and every call is kept; replay a
 * recording later to see whether the service still answers the same way. The engine records and replays; this shows.
 */
@Component({
  selector: 'dd-recorder-view',
  imports: [Icon],
  template: `
    <div class="bar">
      <h3>Traffic recorder</h3>
      <span class="dim small">Calls to a service go through a recording port: point the caller (browser, other service) at it.</span>
    </div>
    @if (error(); as e) { <p class="problem small">{{ e }}</p> }
    <div class="start">
      <select [value]="target()" (change)="target.set($any($event.target).value)">
        @for (t of targets(); track t.service) { <option [value]="t.service">{{ t.service }} :{{ t.ports[0] }}</option> }
      </select>
      <input class="mono" placeholder="recording port (any free one)" [value]="listen()" (input)="listen.set($any($event.target).value)" />
      <button class="primary sm" [disabled]="!target()" (click)="start()"><dd-icon name="radio" [size]="12" /> Start recording</button>
    </div>

    <div class="split">
      <div class="list">
        @for (r of recordings(); track r.id) {
          <button class="row" [class.on]="picked()?.id === r.id" (click)="open(r)">
            @if (r.live) { <span class="rec">● REC</span> }
            <span class="mono name">{{ r.service }}</span>
            <span class="dim small mono">{{ when(r.started) }}</span>
            <span class="grow"></span>
            <span class="mono small">{{ r.count }} calls</span>
          </button>
        } @empty {
          <p class="dim small pad">No recordings yet.</p>
        }
      </div>
      <div class="detail">
        @if (picked(); as r) {
          <div class="dhead">
            <span class="mono">{{ r.service }} :{{ r.targetPort }}</span>
            @if (r.live) {
              <span class="url mono">record through http://127.0.0.1:{{ r.proxyPort }}</span>
              <button class="danger sm" (click)="stop(r)"><dd-icon name="stop" [size]="10" /> Stop</button>
            } @else {
              <button class="sm" [disabled]="replaying() || !calls().length" (click)="replay(r)" title="Send every call again, in order, straight to the service"><dd-icon name="play" [size]="11" /> {{ replaying() ? 'Replaying…' : 'Replay' }}</button>
              <button class="ghost icon sm" title="Delete this recording" (click)="remove(r)"><dd-icon name="trash" [size]="13" /></button>
            }
          </div>
          @if (replayed(); as rp) {
            <div class="summary" [class.bad]="rp.same < rp.total">{{ rp.same }} of {{ rp.total }} calls answered the same way</div>
          }
          <table class="mono small">
            <thead><tr><th>#</th><th>caller</th><th>call</th><th class="n">status</th><th class="n">ms</th>@if (replayed()) { <th>replay</th> }</tr></thead>
            <tbody>
              @for (c of calls(); track c.seq) {
                <tr [class.on]="openCall() === c.seq" (click)="openCall.set(openCall() === c.seq ? null : c.seq)">
                  <td class="dim">{{ c.seq }}</td>
                  <td>{{ c.caller || '–' }}</td>
                  <td class="path"><b>{{ c.method }}</b> {{ c.path }}</td>
                  <td class="n" [class.err]="c.status >= 400">{{ c.status }}</td>
                  <td class="n dim">{{ c.ms }}</td>
                  @if (replayed()) {
                    @let res = resultOf(c.seq);
                    <td [class.err]="res && !res.same" [class.ok]="res?.same">{{ res ? (res.same ? '✓ same' : '✕ ' + (res.differences[0] ?? 'different')) : '' }}</td>
                  }
                </tr>
                @if (openCall() === c.seq) {
                  <tr class="body"><td colspan="6">
                    <div class="block-label">Request</div>
                    <pre>{{ headers(c.requestHeaders) }}{{ c.requestBody ? '\\n\\n' + c.requestBody : '' }}</pre>
                    <div class="block-label">Answer</div>
                    <pre>{{ headers(c.responseHeaders) }}{{ c.responseBody ? '\\n\\n' + c.responseBody : '' }}</pre>
                    @if (resultOf(c.seq); as res) {
                      @if (!res.same) {
                        <div class="block-label">On replay</div>
                        <pre class="err">{{ res.differences.join('\\n') }}</pre>
                      }
                    }
                  </td></tr>
                }
              } @empty {
                <tr><td colspan="6" class="dim">{{ r.live ? 'Waiting for calls on port ' + r.proxyPort + '…' : 'No calls were recorded.' }}</td></tr>
              }
            </tbody>
          </table>
        } @else {
          <p class="dim">Start a recording, or open one on the left.</p>
        }
      </div>
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; padding: 10px 14px; gap: 8px; }
    .bar { display: flex; align-items: baseline; gap: 10px; }
    h3 { margin: 0; font: 700 var(--fs-lg) var(--font-display); }
    .start { display: flex; gap: 6px; }
    select, .start input { height: 28px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 8px; }
    .split { flex: 1; display: flex; gap: 12px; min-height: 0; }
    .list { width: 300px; flex: none; overflow: auto; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-1); }
    .row { display: flex; width: 100%; height: auto; gap: 8px; align-items: center; justify-content: flex-start; padding: 6px 10px; border: none; border-radius: 0; background: transparent; }
    .row:hover { background: var(--bg-2); }
    .row.on { background: var(--accent-weak); }
    .rec { color: var(--err); font-size: var(--fs-xs); font-weight: 700; }
    .grow { flex: 1; }
    .pad { padding: 10px; }
    .detail { flex: 1; min-width: 0; overflow: auto; display: flex; flex-direction: column; gap: 6px; }
    .dhead { display: flex; align-items: center; gap: 10px; }
    .url { color: var(--accent); }
    .summary { color: var(--ok); }
    .summary.bad { color: var(--warn); }
    table { width: 100%; border-collapse: collapse; }
    th { text-align: left; color: var(--text-3); font-weight: 500; border-bottom: 1px solid var(--border); padding: 3px 6px; }
    td { padding: 3px 6px; border-bottom: 1px solid var(--border); vertical-align: top; }
    tbody tr:not(.body) { cursor: pointer; }
    tbody tr:not(.body):hover { background: var(--bg-2); }
    tr.on { background: var(--accent-weak); }
    .path { word-break: break-all; }
    .n { text-align: right; }
    .err { color: var(--err); }
    .ok { color: var(--ok); }
    .block-label { font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin-top: 6px; }
    pre { margin: 2px 0; white-space: pre-wrap; word-break: break-word; max-height: 240px; overflow: auto; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 6px 8px; }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); margin: 0; }
  `,
})
export class RecorderView implements OnInit, OnDestroy {
  readonly engine = inject(Engine);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();

  readonly targets = signal<{ service: string; ports: number[] }[]>([]);
  readonly recordings = signal<RecordingView[]>([]);
  readonly target = signal('');
  readonly listen = signal('');
  readonly picked = signal<RecordingView | null>(null);
  readonly calls = signal<RecordedCall[]>([]);
  readonly openCall = signal<number | null>(null);
  readonly replayed = signal<{ results: ReplayResult[]; same: number; total: number } | null>(null);
  readonly replaying = signal(false);
  readonly error = signal<string | null>(null);
  private timer?: ReturnType<typeof setInterval>;

  readonly results = computed(() => new Map((this.replayed()?.results ?? []).map((r) => [r.seq, r])));

  ngOnInit(): void {
    this.load();
    // While a recording runs, its calls come in live.
    this.timer = setInterval(() => {
      if (this.picked()?.live) this.refreshPicked();
      if (this.recordings().some((r) => r.live)) this.load();
    }, 2000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  async load(): Promise<void> {
    const r = await this.engine.recorder();
    if (!r.ok) return this.error.set(r.error);
    this.targets.set(r.value.targets);
    this.recordings.set(r.value.recordings);
    if (!this.target() && r.value.targets.length) this.target.set(r.value.targets[0].service);
  }

  async start(): Promise<void> {
    this.error.set(null);
    const listen = Number(this.listen()) || null;
    const r = await this.engine.startRecording(this.target(), null, listen);
    if (!r.ok) return this.error.set(r.error);
    await this.load();
    await this.open(r.value);
  }

  async stop(rec: RecordingView): Promise<void> {
    const r = await this.engine.stopRecording(rec.id);
    if (!r.ok) return this.error.set(r.error);
    await this.load();
    await this.open(r.value);
  }

  async open(rec: RecordingView): Promise<void> {
    this.picked.set(rec);
    this.replayed.set(null);
    this.openCall.set(null);
    await this.refreshPicked();
  }

  private async refreshPicked(): Promise<void> {
    const p = this.picked();
    if (!p) return;
    const r = await this.engine.recording(p.id);
    if (!r.ok) return this.error.set(r.error);
    const { calls, ...summary } = r.value;
    this.picked.set(summary);
    this.calls.set(calls);
  }

  async replay(rec: RecordingView): Promise<void> {
    if (!confirm(`Send all ${this.calls().length} calls to ${rec.service} again, in order? Calls that change data (POST, DELETE…) change it again.`)) return;
    this.replaying.set(true);
    const r = await this.engine.replay(rec.id);
    this.replaying.set(false);
    if (r.ok) this.replayed.set(r.value);
    else this.error.set(r.error);
  }

  async remove(rec: RecordingView): Promise<void> {
    if (!confirm(`Delete the recording of ${rec.service} (${rec.count} calls)?`)) return;
    const r = await this.engine.deleteRecording(rec.id);
    if (!r.ok) return this.error.set(r.error);
    this.picked.set(null);
    await this.load();
  }

  resultOf(seq: number): ReplayResult | undefined {
    return this.results().get(seq);
  }

  headers(h: Record<string, string[]>): string {
    return Object.entries(h)
      .map(([k, v]) => `${k}: ${v.join(', ')}`)
      .join('\n');
  }

  when(iso: string): string {
    const d = new Date(iso);
    return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }) + ' ' + d.toLocaleTimeString('en-GB', { hour12: false });
  }
}
