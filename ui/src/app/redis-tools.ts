import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Engine, MemoryView, ServerStatsView, SlowLogView, WatchEventView } from './engine';
import { Doc, RedisTool, Workbench } from './workbench';
import { Icon } from './icon';
import { SparkChart } from './spark-chart';
import { bytes } from './format';

const SHOWN = 1000;

/** A live stream (MONITOR or pub/sub) the engine keeps open while this tab polls it. */
class Stream {
  readonly id = signal<string | null>(null);
  readonly events = signal<WatchEventView[]>([]);
  readonly ended = signal<string | null>(null);
  readonly paused = signal(false);
  after = 0;
}

/** Everything one Insights tab keeps while switching views. */
class ToolsState {
  readonly memory = signal<MemoryView | null>(null);
  readonly memoryPattern = signal('*');
  readonly slow = signal<SlowLogView | null>(null);
  readonly monitor = new Stream();
  readonly pubsub = new Stream();
  readonly patterns = signal('*');
  /** Server counters sampled every 2 s while the Server stats view is shown (the last 10 minutes kept). */
  readonly samples = signal<ServerStatsView[]>([]);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
}

/**
 * Redis insights: where the memory goes, the slow log, every command live (MONITOR), and pub/sub channels. The
 * streams run on the engine's own connections and stop when this tab closes.
 */
@Component({
  selector: 'dd-redis-tools',
  imports: [Icon, SparkChart],
  template: `
    @let st = s();
    <div class="bar">
      <div class="segmented">
        <button [class.on]="tool() === 'memory'" (click)="go('memory')">Memory</button>
        <button [class.on]="tool() === 'slowlog'" (click)="go('slowlog')">Slow log</button>
        <button [class.on]="tool() === 'monitor'" (click)="go('monitor')">Live commands @if (st.monitor.id()) { <span class="live"></span> }</button>
        <button [class.on]="tool() === 'pubsub'" (click)="go('pubsub')">Pub/Sub @if (st.pubsub.id()) { <span class="live"></span> }</button>
        <button [class.on]="tool() === 'stats'" (click)="go('stats')">Server stats</button>
      </div>
      <span class="grow"></span>
      <span class="mono dim">{{ service() }}</span>
    </div>
    @if (st.error(); as e) { <div class="error mono">{{ e }}</div> }

    @switch (tool()) {
      @case ('memory') {
        <div class="tools">
          <input #mp class="mono pat" [value]="st.memoryPattern()" placeholder="pattern" (keydown.enter)="st.memoryPattern.set(mp.value); loadMemory()" />
          <button class="sm" [class.busy]="st.busy()" (click)="st.memoryPattern.set(mp.value); loadMemory()"><dd-icon name="restart" [size]="12" /> Measure</button>
        </div>
        @if (st.memory(); as m) {
          <div class="cards">
            <div class="card"><span class="k">used</span><span class="v mono">{{ bytes(m.usedBytes) }}</span>@if (m.maxBytes) { <span class="dim small">of {{ bytes(m.maxBytes) }} limit</span> } @else { <span class="dim small">no limit set</span> }</div>
            <div class="card"><span class="k">keys</span><span class="v mono">{{ m.totalKeys.toLocaleString() }}</span><span class="dim small">{{ m.scanned < m.totalKeys ? m.scanned.toLocaleString() + ' measured' : 'all measured' }}</span></div>
            <div class="card" [class.warn]="m.evicted > 0"><span class="k">at the limit</span><span class="v mono">{{ m.policy }}</span><span class="dim small">{{ m.evicted.toLocaleString() }} keys evicted so far</span></div>
          </div>
          @if (m.maxBytes && m.usedBytes > m.maxBytes * 0.8) {
            <div class="hint"><dd-icon name="diagnosis" [size]="14" />
              <span>Redis uses {{ (100 * m.usedBytes / m.maxBytes).toFixed(0) }}% of its limit. With "{{ m.policy }}" it will {{ m.policy === 'noeviction' ? 'refuse writes (OOM errors in your app)' : 'start deleting keys to make room' }} when it's full.</span>
            </div>
          }
          <div class="cols">
            <div>
              <h4>By prefix</h4>
              <table class="mono">
                @for (p of m.prefixes; track p.prefix) {
                  <tr>
                    <td><a (click)="browse(p.prefix)">{{ p.prefix }}</a></td>
                    <td class="num dim">{{ p.keys.toLocaleString() }} keys</td>
                    <td class="num">{{ bytes(p.bytes) }}</td>
                    <td class="barcell"><span class="fill" [style.width.%]="100 * p.bytes / m.prefixes[0].bytes"></span></td>
                  </tr>
                }
              </table>
            </div>
            <div>
              <h4>Biggest keys</h4>
              <table class="mono">
                @for (k of m.biggest; track k.key) {
                  <tr>
                    <td class="key"><a (click)="openKey(k.key)" [title]="k.key">{{ k.key }}</a></td>
                    <td class="dim">{{ k.type }}</td>
                    <td class="num dim">{{ k.length >= 0 && k.type !== 'none' ? k.length.toLocaleString() : '' }}</td>
                    <td class="num">{{ bytes(k.bytes) }}</td>
                  </tr>
                }
              </table>
            </div>
          </div>
        } @else if (st.busy()) {
          <div class="none">Measuring every key with MEMORY USAGE…</div>
        }
      }
      @case ('slowlog') {
        <div class="tools">
          <button class="sm" (click)="loadSlow()"><dd-icon name="restart" [size]="12" /> Reload</button>
          <button class="sm ghost" (click)="resetSlow()" title="SLOWLOG RESET: start with an empty log">Clear</button>
          @if (st.slow(); as l) { <span class="dim small">Commands slower than {{ l.thresholdMicros < 0 ? '?' : (l.thresholdMicros / 1000).toLocaleString() + ' ms' }} are logged (slowlog-log-slower-than).</span> }
        </div>
        @if (st.slow(); as l) {
          <div class="grid">
            <table class="mono">
              <thead><tr><th>when</th><th class="num">took</th><th>command</th><th>client</th></tr></thead>
              <tbody>
                @for (e of l.entries; track e.id) {
                  <tr>
                    <td class="dim">{{ time(e.at) }}</td>
                    <td class="num" [class.warn]="e.micros > 100000">{{ (e.micros / 1000).toFixed(e.micros < 10000 ? 2 : 0) }} ms</td>
                    <td class="cmd" [title]="e.words.join(' ')">{{ e.words.join(' ') }}</td>
                    <td class="dim">{{ e.client }}</td>
                  </tr>
                } @empty {
                  <tr><td colspan="4" class="none">Nothing slow so far.</td></tr>
                }
              </tbody>
            </table>
          </div>
          @if (hasKeys(l)) {
            <div class="hint"><dd-icon name="diagnosis" [size]="14" /><span>KEYS reads the whole keyspace at once and blocks every other client meanwhile. Use SCAN in your app instead.</span></div>
          }
        }
      }
      @case ('monitor') {
        <div class="tools">
          @if (st.monitor.id()) {
            <button class="sm danger" (click)="stopStream(st.monitor)"><dd-icon name="stop" [size]="11" /> Stop</button>
            <button class="sm ghost" (click)="st.monitor.paused.set(!st.monitor.paused())">{{ st.monitor.paused() ? 'Resume' : 'Pause' }}</button>
          } @else {
            <button class="sm primary" (click)="startMonitor()"><dd-icon name="play" [size]="11" /> Start</button>
          }
          <button class="sm ghost" (click)="st.monitor.events.set([])">Clear</button>
          <input #mf class="mono pat" placeholder="filter, e.g. user:" (input)="monitorFilter.set(mf.value)" />
          <span class="dim small">MONITOR shows every command any client sends. It slows Redis down a little: stop it when done.</span>
        </div>
        @if (st.monitor.ended(); as e) { <div class="error mono">The stream ended: {{ e }}</div> }
        <div class="stream mono">
          @for (e of shownMonitor(); track e.seq) {
            <div class="ev"><span class="t">{{ time(e.at, true) }}</span><span class="src">{{ e.source }}</span><span class="txt">{{ e.text }}</span></div>
          } @empty {
            <div class="none">{{ st.monitor.id() ? 'Waiting for commands…' : 'Press Start to see every command Redis runs, as it runs it.' }}</div>
          }
        </div>
      }
      @case ('stats') {
        @if (st.samples().length; as n) {
          @let last = st.samples()[n - 1];
          <div class="tools dim small">Redis {{ last.version }} · up {{ uptime(last.uptimeSeconds) }} · sampled every 2 s while this view is open</div>
          <div class="charts">
            <dd-spark title="Commands per second" [values]="series('ops')" [times]="times()" />
            <dd-spark title="Connected clients" [values]="series('clients')" [times]="times()" />
            <dd-spark title="Memory used" [values]="series('memory')" [times]="times()" [fmt]="bytes" />
            <dd-spark title="Keys" [values]="series('keys')" [times]="times()" [fmt]="count" />
            <dd-spark title="Cache hit rate (%)" [values]="series('hitRate')" [times]="times()" [fmt]="percent" />
            <dd-spark title="Network in per second" [values]="series('netIn')" [times]="times()" [fmt]="bytes" />
            <dd-spark title="Network out per second" [values]="series('netOut')" [times]="times()" [fmt]="bytes" />
            <dd-spark title="Keys expired per second" [values]="series('expired')" [times]="times()" />
          </div>
          @if (last.blockedClients > 0) { <div class="tools small warn-text">{{ last.blockedClients }} client(s) are blocked waiting (BLPOP, XREAD BLOCK…): normal for workers, a problem if the app waits on them.</div> }
          @if (last.evictedKeys > 0) { <div class="tools small warn-text">Redis has thrown away {{ last.evictedKeys }} keys since it started because memory was full (see Memory).</div> }
        } @else {
          <div class="none">Reading the server's counters…</div>
        }
      }
      @case ('pubsub') {
        <div class="tools">
          <input #pp class="mono pat wide" [value]="st.patterns()" placeholder="channels: orders.* notifications" (keydown.enter)="st.patterns.set(pp.value); subscribe()" />
          @if (st.pubsub.id()) {
            <button class="sm danger" (click)="stopStream(st.pubsub)"><dd-icon name="stop" [size]="11" /> Unsubscribe</button>
          } @else {
            <button class="sm primary" (click)="st.patterns.set(pp.value); subscribe()"><dd-icon name="radio" [size]="12" /> Subscribe</button>
          }
          <button class="sm ghost" (click)="st.pubsub.events.set([])">Clear</button>
        </div>
        <div class="publish">
          <input #pc class="mono ch" placeholder="channel" />
          <input #pm class="mono grow-in" placeholder="message" (keydown.enter)="publish(pc.value, pm.value); pm.value = ''" />
          <button class="sm" (click)="publish(pc.value, pm.value); pm.value = ''"><dd-icon name="send" [size]="12" /> Publish</button>
        </div>
        @if (st.pubsub.ended(); as e) { <div class="error mono">The subscription ended: {{ e }}</div> }
        <div class="stream mono">
          @for (e of st.pubsub.events(); track e.seq) {
            <div class="ev"><span class="t">{{ time(e.at, true) }}</span><span class="src ch">{{ e.source }}</span><span class="txt">{{ e.text }}</span></div>
          } @empty {
            <div class="none">{{ st.pubsub.id() ? 'Subscribed. Messages show up here as they are published.' : 'Subscribe to channel patterns (* matches anything) to see their messages live.' }}</div>
          }
        </div>
      }
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; height: 100%; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 10px; padding: 8px 14px; border-bottom: 1px solid var(--border); }
    .charts { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 10px; padding: 0 14px 14px; overflow: auto; }
    .warn-text { color: var(--warn); }
    .live { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: var(--accent); margin-left: 4px; animation: pulse 1.2s infinite; }
    @keyframes pulse { 50% { opacity: 0.3; } }
    .grow { flex: 1; }
    .dim { color: var(--text-3); }
    .small { font-size: var(--fs-sm); }
    .warn { color: var(--warn); }
    .tools, .publish { display: flex; align-items: center; gap: 8px; padding: 8px 14px; flex-wrap: wrap; }
    .publish { padding-top: 0; }
    .pat { height: 26px; width: 200px; font-size: var(--fs-sm); }
    .pat.wide { width: 320px; }
    .ch { height: 26px; width: 180px; font-size: var(--fs-sm); }
    .grow-in { height: 26px; flex: 1; font-size: var(--fs-sm); }
    .error { margin: 6px 14px; padding: 8px 12px; background: var(--err-bg); border: 1px solid rgba(240, 100, 90, 0.4); border-radius: var(--radius); white-space: pre-wrap; font-size: var(--fs-sm); }
    .cards { display: flex; gap: 10px; padding: 4px 14px 10px; }
    .card { display: flex; flex-direction: column; gap: 2px; padding: 10px 12px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-1); min-width: 180px; }
    .card.warn { border-color: var(--warn); }
    .card .k { font: 500 var(--fs-xs) var(--font-mono); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; }
    .card .v { font-size: 18px; color: var(--text-1); }
    .hint { display: flex; gap: 8px; margin: 0 14px 10px; padding: 8px 10px; border-radius: var(--radius); background: var(--warn-bg); border: 1px solid var(--warn); font-size: var(--fs-sm); }
    .hint dd-icon { color: var(--warn); flex: none; margin-top: 2px; }
    .cols { display: grid; grid-template-columns: 1fr 1.4fr; gap: 18px; padding: 0 14px 14px; overflow: auto; min-height: 0; flex: 1; }
    h4 { font: 500 var(--fs-xs) var(--font-mono); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin: 4px 0 6px; }
    table { border-collapse: collapse; width: 100%; font-size: var(--fs-sm); }
    td, th { padding: 4px 8px; border-bottom: 1px solid var(--border); text-align: left; white-space: nowrap; }
    th { position: sticky; top: 0; background: var(--bg-1); }
    .num { text-align: right; }
    .key { max-width: 360px; overflow: hidden; text-overflow: ellipsis; }
    .cmd { max-width: 640px; overflow: hidden; text-overflow: ellipsis; }
    .barcell { width: 30%; }
    .fill { display: block; height: 6px; border-radius: 3px; background: var(--accent); opacity: 0.7; }
    a { color: var(--accent); cursor: pointer; }
    .grid { flex: 1; overflow: auto; min-height: 0; padding: 0 14px; }
    .stream { flex: 1; overflow: auto; min-height: 0; padding: 4px 14px 14px; font-size: var(--fs-sm); display: flex; flex-direction: column-reverse; }
    .ev { display: flex; gap: 10px; padding: 2px 0; border-bottom: 1px solid var(--border); }
    .ev .t { color: var(--text-3); flex: none; }
    .ev .src { color: var(--text-3); flex: none; min-width: 150px; }
    .ev .src.ch { color: var(--accent); }
    .ev .txt { white-space: pre-wrap; word-break: break-all; }
    .none { color: var(--text-3); padding: 14px; }
  `,
})
export class RedisTools {
  readonly engine = inject(Engine);
  readonly wb = inject(Workbench);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();
  readonly bytes = bytes;
  readonly monitorFilter = signal('');
  readonly spec = computed(() => this.doc().spec as { kind: 'redis-tools'; service: string; tool: RedisTool });
  readonly service = computed(() => this.spec().service);
  readonly tool = computed(() => this.spec().tool);
  readonly s = computed(() => {
    const d = this.doc();
    if (!(d.state instanceof ToolsState)) d.state = new ToolsState();
    return d.state as ToolsState;
  });
  /** Newest first (the list is drawn bottom-up), filtered, at most {@link SHOWN}. */
  readonly shownMonitor = computed(() => {
    const f = this.monitorFilter().toLowerCase();
    const all = this.s().monitor.events();
    const list = f ? all.filter((e) => e.text.toLowerCase().includes(f) || e.source.includes(f)) : all;
    return list.slice(-SHOWN).reverse();
  });
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    effect(() => {
      const tool = this.tool();
      const st = this.s();
      untracked(() => {
        if (tool === 'memory' && !st.memory()) this.loadMemory();
        if (tool === 'slowlog') this.loadSlow();
      });
    });
    // Streams are polled while this tab is shown; when the tab closes, they are stopped (see onDestroy).
    this.timer = setInterval(() => this.poll(), 500);
    inject(DestroyRef).onDestroy(() => {
      if (this.timer) clearInterval(this.timer);
      if (!this.wb.docs().some((d) => d.id === this.doc().id)) {
        const st = this.s();
        for (const stream of [st.monitor, st.pubsub]) {
          const id = stream.id();
          if (id) this.engine.stopWatch(this.service(), id);
        }
      }
    });
  }

  readonly times = computed(() => this.s().samples().map((x) => x.at));

  /**
   * Gauges as they are; totals (hits, bytes, expired keys) turned into a rate per second between two samples. A
   * restart of Redis resets the totals: that step counts as 0 rather than a negative spike.
   */
  series(kind: 'ops' | 'clients' | 'memory' | 'keys' | 'hitRate' | 'netIn' | 'netOut' | 'expired'): number[] {
    const l = this.s().samples();
    const rate = (pick: (x: ServerStatsView) => number) =>
      l.map((x, i) => {
        if (i === 0) return 0;
        const secs = (new Date(x.at).getTime() - new Date(l[i - 1].at).getTime()) / 1000 || 1;
        return Math.max(0, (pick(x) - pick(l[i - 1])) / secs);
      });
    switch (kind) {
      case 'ops':
        return l.map((x) => x.opsPerSecond);
      case 'clients':
        return l.map((x) => x.clients);
      case 'memory':
        return l.map((x) => x.usedBytes);
      case 'keys':
        return l.map((x) => x.keys);
      case 'hitRate':
        return l.map((x, i) => {
          const prev = l[i - 1];
          const hits = prev ? x.hits - prev.hits : x.hits;
          const misses = prev ? x.misses - prev.misses : x.misses;
          return hits + misses > 0 ? (100 * hits) / (hits + misses) : prev ? 100 : 0;
        });
      case 'netIn':
        return rate((x) => x.netInBytes);
      case 'netOut':
        return rate((x) => x.netOutBytes);
      case 'expired':
        return rate((x) => x.expiredKeys);
    }
  }

  readonly count = (n: number) => Math.round(n).toLocaleString('en-US');
  readonly percent = (n: number) => `${Math.round(n)}%`;
  /** Network rates are often under 1 KB/s: "312 B", not "0K". */

  uptime(s: number): string {
    return s < 3600 ? `${Math.round(s / 60)} min` : s < 86400 ? `${Math.round(s / 3600)} h` : `${Math.round(s / 86400)} days`;
  }

  go(tool: RedisTool): void {
    this.wb.open({ kind: 'redis-tools', service: this.service(), tool });
  }

  async loadMemory(): Promise<void> {
    const st = this.s();
    st.busy.set(true);
    const r = await this.engine.redisMemory(this.service(), st.memoryPattern() || '*');
    st.busy.set(false);
    st.error.set(r.ok ? null : r.error);
    if (r.ok) st.memory.set(r.value);
  }

  async loadSlow(): Promise<void> {
    const r = await this.engine.slowLog(this.service());
    this.s().error.set(r.ok ? null : r.error);
    if (r.ok) this.s().slow.set(r.value);
  }

  async resetSlow(): Promise<void> {
    await this.engine.resetSlowLog(this.service());
    this.loadSlow();
  }

  hasKeys(l: SlowLogView): boolean {
    return l.entries.some((e) => e.words[0]?.toUpperCase() === 'KEYS');
  }

  startMonitor(): Promise<void> {
    return this.start(this.s().monitor, { kind: 'monitor' });
  }

  subscribe(): Promise<void> {
    const patterns = this.s().patterns().split(/[\s,]+/).filter(Boolean);
    if (!patterns.length) return Promise.resolve();
    return this.start(this.s().pubsub, { kind: 'subscribe', patterns });
  }

  private async start(stream: Stream, what: { kind: 'monitor' } | { kind: 'subscribe'; patterns: string[] }): Promise<void> {
    const r = await this.engine.watch(this.service(), what);
    this.s().error.set(r.ok ? null : r.error);
    if (r.ok) {
      stream.id.set(r.value.id);
      stream.ended.set(null);
      stream.after = 0;
    }
  }

  async stopStream(stream: Stream): Promise<void> {
    const id = stream.id();
    stream.id.set(null);
    if (id) await this.engine.stopWatch(this.service(), id);
  }

  async publish(channel: string, message: string): Promise<void> {
    if (!channel.trim()) return;
    const r = await this.engine.publish(this.service(), channel.trim(), message);
    this.s().error.set(r.ok ? null : r.error);
    if (r.ok) this.toast.emit(`Published to ${channel.trim()}: ${r.value.receivers} subscriber${r.value.receivers === 1 ? '' : 's'} got it`);
  }

  private lastSample = 0;

  private async poll(): Promise<void> {
    const st = this.s();
    if (this.tool() === 'stats' && Date.now() - this.lastSample >= 2000) {
      this.lastSample = Date.now();
      const r = await this.engine.redisServer(this.service());
      st.error.set(r.ok ? null : r.error);
      if (r.ok) st.samples.update((l) => [...l, r.value].slice(-300));
    }
    for (const stream of [st.monitor, st.pubsub]) {
      const id = stream.id();
      if (!id || stream.paused()) continue;
      const r = await this.engine.watchEvents(this.service(), id, stream.after);
      if (!r.ok) {
        stream.id.set(null);
        stream.ended.set(r.error);
        continue;
      }
      if (r.value.events.length) {
        stream.after = r.value.events[r.value.events.length - 1].seq;
        stream.events.update((e) => [...e, ...r.value.events].slice(-5000));
      }
      if (r.value.ended) {
        stream.id.set(null);
        if (r.value.ended !== 'stopped') stream.ended.set(r.value.ended);
      }
    }
  }

  browse(prefix: string): void {
    this.wb.open({ kind: 'redis', service: this.service(), pattern: prefix === '(no prefix)' ? '*' : prefix });
  }

  openKey(key: string): void {
    this.wb.open({ kind: 'redis', service: this.service(), pattern: key });
  }

  time(iso: string, precise = false): string {
    const d = new Date(iso);
    return precise ? d.toLocaleTimeString(undefined, { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0') : d.toLocaleString();
  }
}
