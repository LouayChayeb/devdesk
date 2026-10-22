import { Component, OnDestroy, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Engine, QueueMessage, QueueReader, QueueSummary } from './engine';
import { Doc } from './workbench';
import { Icon } from './icon';

/**
 * One broker's queues (RabbitMQ) or topics (Kafka): what waits in each, the messages themselves (looked at, never
 * taken), who reads them, and a box to send one. The engine talks to the broker; this only shows and asks.
 */
@Component({
  selector: 'dd-queue-view',
  imports: [Icon],
  template: `
    @if (error(); as e) { <p class="problem small">{{ e }}</p> }
    <div class="split">
      <div class="list">
        <div class="lhead small dim">
          <span class="grow">{{ kafka() ? 'Topics' : 'Queues' }}</span>
          <span class="n">{{ kafka() ? 'messages' : 'waiting' }}</span>
          <span class="n">{{ kafka() ? 'unread' : 'unacked' }}</span>
          <span class="n">readers</span>
        </div>
        @for (q of queues(); track q.name) {
          <button class="row" [class.on]="picked() === q.name" (click)="pick(q.name)">
            <span class="grow mono name">{{ q.name }}</span>
            <span class="n mono" [class.warn]="!kafka() && q.waiting > 0 && q.readers === 0">{{ q.waiting }}</span>
            <span class="n mono dim">{{ q.inFlight }}</span>
            <span class="n mono" [class.warn]="q.readers === 0 && !sqs()" [title]="sqs() ? 'SQS keeps no list of its readers' : ''">{{ sqs() ? '–' : q.readers }}</span>
          </button>
        } @empty {
          <p class="dim small pad">No {{ kafka() ? 'topics' : 'queues' }} yet. They appear when the app declares them, or send a first message to one.</p>
        }
      </div>
      <div class="detail">
        @if (picked(); as q) {
          <div class="dhead"><span class="mono name">{{ q }}</span><span class="grow"></span>
            <button class="ghost icon sm" title="Reload" (click)="loadQueue()"><dd-icon name="restart" [size]="13" /></button>
          </div>
          <div class="block-label">Who reads it</div>
          @for (r of readers(); track $index) {
            <div class="reader mono small">
              <span>{{ r.name }}</span>
              @if (r.client) { <span class="dim">· {{ r.client }}</span> }
              @if (r.from) { <span class="dim">· from {{ r.from }}</span> }
              @if (r.behind >= 0) { <span [class.warn]="r.behind > 0">· {{ r.behind }} behind</span> }
            </div>
          } @empty {
            <div class="warn small">Nobody reads it right now: messages sent here pile up.</div>
          }
          @if (connected(); as c) { <div class="dim small">Connected to {{ service() }} from this machine: {{ c }}</div> }

          <div class="block-label">{{ kafka() ? 'Newest messages' : 'Next messages' }} <span class="dim">· {{ kafka() ? 'read without joining any group' : 'looked at and put back' }}</span></div>
          <div class="messages">
            @for (m of messages(); track m.position) {
              <div class="msg">
                <div class="mhead mono small dim">
                  <span>#{{ m.position }}</span>
                  @if (m.key) { <span>key {{ m.key }}</span> }
                  @if (m.at) { <span>{{ when(m.at) }}</span> }
                  @for (h of headerList(m); track h) { <span>{{ h }}</span> }
                </div>
                <pre class="mono">{{ pretty(m.body) }}</pre>
              </div>
            } @empty {
              <p class="dim small">Empty.</p>
            }
          </div>

          <div class="block-label">Send a message</div>
          <textarea class="mono" rows="4" placeholder='{"order": 42}' [value]="draft()" (input)="draft.set($any($event.target).value)"></textarea>
          <div class="send">
            @if (kafka()) { <input class="mono" placeholder="key (optional)" [value]="key()" (input)="key.set($any($event.target).value)" /> }
            <span class="grow"></span>
            <button class="primary sm" [disabled]="!draft().trim()" (click)="send()"><dd-icon name="send" [size]="12" /> Send to {{ q }}</button>
          </div>
        } @else {
          <p class="dim">Pick a {{ kafka() ? 'topic' : 'queue' }} to see its messages and who reads it.</p>
        }
      </div>
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; padding: 10px 14px; gap: 8px; }
    .split { flex: 1; display: flex; gap: 12px; min-height: 0; }
    .list { width: 360px; flex: none; overflow: auto; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-1); }
    .lhead, .row { display: flex; align-items: center; gap: 8px; padding: 4px 10px; }
    .lhead { border-bottom: 1px solid var(--border); text-transform: uppercase; letter-spacing: 0.04em; font-size: var(--fs-xs); }
    .row { width: 100%; height: auto; justify-content: flex-start; border: none; border-radius: 0; background: transparent; text-align: left; }
    .row:hover { background: var(--bg-2); }
    .row.on { background: var(--accent-weak); }
    .grow { flex: 1; min-width: 0; }
    .name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--fs-sm); }
    .n { width: 62px; text-align: right; font-size: var(--fs-sm); font-variant-numeric: tabular-nums; }
    .warn { color: var(--warn); }
    .pad { padding: 10px; }
    .detail { flex: 1; min-width: 0; overflow: auto; display: flex; flex-direction: column; gap: 6px; }
    .dhead { display: flex; align-items: center; gap: 6px; }
    .block-label { font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin-top: 8px; }
    .reader { display: flex; gap: 6px; flex-wrap: wrap; }
    .messages { display: flex; flex-direction: column; gap: 6px; }
    .msg { border: 1px solid var(--border); border-radius: var(--radius-badge); background: var(--bg-1); padding: 6px 10px; }
    .mhead { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 4px; }
    .msg pre { margin: 0; font-size: var(--fs-sm); white-space: pre-wrap; word-break: break-word; max-height: 240px; overflow: auto; }
    textarea { background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 6px 8px; resize: vertical; }
    .send { display: flex; gap: 6px; align-items: center; }
    .send input { height: 28px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 8px; }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); margin: 0; }
  `,
})
export class QueueView implements OnDestroy {
  readonly engine = inject(Engine);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();

  readonly queues = signal<QueueSummary[]>([]);
  readonly picked = signal<string | null>(null);
  readonly messages = signal<QueueMessage[]>([]);
  readonly readers = signal<QueueReader[]>([]);
  readonly connectedMap = signal<Record<string, number>>({});
  readonly error = signal<string | null>(null);
  readonly draft = signal('');
  readonly key = signal('');
  readonly kind = signal<'rabbitmq' | 'kafka' | 'sqs' | null>(null);
  private readonly timer = setInterval(() => this.load(), 4000);

  readonly service = computed(() => (this.doc().spec.kind === 'queues' ? (this.doc().spec as { service: string }).service : ''));
  readonly kafka = computed(() => this.kind() === 'kafka');
  readonly sqs = computed(() => this.kind() === 'sqs');
  readonly connected = computed(() =>
    Object.entries(this.connectedMap())
      .map(([s, n]) => (n > 1 ? `${s} (${n} connections)` : s))
      .join(', '),
  );

  constructor() {
    effect(() => {
      this.doc();
      untracked(async () => {
        const b = await this.engine.brokers();
        if (b.ok) this.kind.set(b.value.find((x) => x.service === this.service())?.kind ?? null);
        await this.load();
      });
    });
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  async load(): Promise<void> {
    const r = await this.engine.queues(this.service());
    if (r.ok) {
      this.queues.set(r.value);
      this.error.set(null);
    } else {
      this.error.set(r.error);
    }
  }

  async pick(name: string): Promise<void> {
    this.picked.set(name);
    this.messages.set([]);
    this.readers.set([]);
    await this.loadQueue();
  }

  async loadQueue(): Promise<void> {
    const q = this.picked();
    if (!q) return;
    const [m, r] = await Promise.all([this.engine.queueMessages(this.service(), q, 20), this.engine.queueReaders(this.service(), q)]);
    if (this.picked() !== q) return;
    if (m.ok) this.messages.set(m.value);
    else this.error.set(m.error);
    if (r.ok) {
      this.readers.set(r.value.readers);
      this.connectedMap.set(r.value.connected);
    }
  }

  async send(): Promise<void> {
    const q = this.picked();
    if (!q) return;
    const r = await this.engine.sendToQueue(this.service(), q, this.draft(), this.key());
    if (!r.ok) return this.error.set(r.error);
    this.toast.emit(`Sent to ${q}`);
    this.draft.set('');
    await Promise.all([this.load(), this.loadQueue()]);
  }

  headerList(m: QueueMessage): string[] {
    return Object.entries(m.headers).map(([k, v]) => `${k}: ${v}`);
  }

  /** JSON bodies indented, anything else as it came. */
  pretty(body: string): string {
    const t = body.trim();
    if (!t.startsWith('{') && !t.startsWith('[')) return body;
    try {
      return JSON.stringify(JSON.parse(t), null, 2);
    } catch {
      return body;
    }
  }

  when(iso: string): string {
    const d = new Date(iso);
    return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }) + ' ' + d.toLocaleTimeString('en-GB', { hour12: false });
  }
}
