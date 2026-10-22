import { Component, computed, effect, inject, output, signal, untracked } from '@angular/core';
import { BrokerView, Engine, MailCatcherView } from './engine';
import { Doc, Workbench } from './workbench';
import { DocTabs } from './doc-tabs';
import { Panes } from './panes';
import { Icon } from './icon';
import { StatusDot } from './status-dot';

/**
 * The Workbench's Inbox page: the project's mail catchers (Mailpit) and message brokers (RabbitMQ, Kafka) on the left, a
 * tab for each.
 */
@Component({
  selector: 'dd-mail',
  imports: [DocTabs, Panes, Icon, StatusDot],
  template: `
    <aside class="side">
      <div class="label">Mail catchers</div>
      @for (c of catchers(); track c.service) {
        <button class="item" [class.on]="isOpen(c.service)" (click)="open(c.service)" [title]="c.url + ' · SMTP port ' + c.smtpPort">
          <dd-dot [label]="c.state.label" />
          <span class="grow-text mono">{{ c.service }}</span>
          <span class="dim mono small">{{ c.url.split(':').pop() }}</span>
        </button>
        <div class="info mono">SMTP {{ c.smtpPort > 0 ? 'localhost:' + c.smtpPort : 'port not published' }}</div>
      } @empty {
        @if (loaded()) {
          <div class="help">
            <p>No mail catcher in this project.</p>
            <p class="dim">Add Mailpit to devdesk.yaml and point the app's SMTP settings at it. Every mail it sends then shows up here instead of in a real inbox:</p>
            <pre class="mono">{{ example }}</pre>
          </div>
        }
      }
      <div class="label queues-label">Queues</div>
      @for (b of brokers(); track b.service) {
        <button class="item" [class.on]="isQueues(b.service)" (click)="openQueues(b.service)" [title]="b.kind + ' · ' + b.service">
          <dd-dot [label]="b.state.label" />
          <span class="grow-text mono">{{ b.service }}</span>
          <span class="dim mono small">{{ b.kind === 'rabbitmq' ? 'rabbit' : b.kind }}</span>
        </button>
        @if (connectedText(b); as c) { <div class="info">{{ c }}</div> }
      } @empty {
        @if (loaded()) {
          <div class="help dim">No RabbitMQ (a "-management" image with port 15672), Kafka or local SQS in this project.</div>
        }
      }
    </aside>
    <section class="main">
      <dd-doc-tabs (show)="show.emit($event)" />
      <dd-panes [left]="current()" (toast)="toast.emit($event)" (show)="show.emit($event)">
        <div class="none">
          <dd-icon name="mail" [size]="28" />
          <p>Open a mail catcher or a queue on the left to see what the app sent.</p>
        </div>
      </dd-panes>
    </section>
  `,
  styles: `
    :host { display: grid; grid-template-columns: 220px 1fr; height: 100%; min-height: 0; }
    .side { border-right: 1px solid var(--border); padding: 10px 8px; overflow: auto; display: flex; flex-direction: column; gap: 2px; background: var(--bg-1); }
    .label { font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; padding: 4px 6px 8px; }
    .item { justify-content: flex-start; gap: 8px; background: transparent; border-color: transparent; height: 28px; padding: 0 8px; }
    .item:hover:not(:disabled) { background: var(--bg-2); border-color: transparent; }
    .item.on { background: var(--accent-weak); }
    .queues-label { margin-top: 14px; }
    .info { color: var(--text-3); font-size: var(--fs-xs); padding: 0 8px 6px 24px; }
    .help { padding: 4px 6px; font-size: var(--fs-sm); }
    .help pre { background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius); padding: 8px; font-size: var(--fs-xs); white-space: pre; overflow: auto; }
    .main { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
    .main > dd-panes { flex: 1; min-height: 0; }
    .none { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; flex: 1; color: var(--text-3); }
  `,
})
export class MailPanel {
  readonly engine = inject(Engine);
  readonly wb = inject(Workbench);
  readonly show = output<Doc>();
  readonly toast = output<string>();
  readonly catchers = signal<MailCatcherView[]>([]);
  readonly brokers = signal<BrokerView[]>([]);
  readonly loaded = signal(false);
  readonly example = `mailpit:
  container:
    image: axllent/mailpit:latest
    ports: ["8025:8025", "1025:1025"]
  ports: [8025, 1025]`;

  readonly current = computed(() => {
    void this.wb.activeId();
    return this.wb.lastOn('mail');
  });

  constructor() {
    let first = true;
    // Re-read when services change state (the dot), and open the only catcher right away the first time.
    effect(() => {
      void (this.engine.project()?.services ?? []).map((s) => s.name + s.state.label).join();
      untracked(async () => {
        const list = await this.engine.mailCatchers();
        this.catchers.set(list);
        const brokers = await this.engine.brokers();
        this.brokers.set(brokers.ok ? brokers.value : []);
        this.loaded.set(true);
        if (first && list.length === 1 && !this.wb.lastOn('mail')) this.open(list[0].service);
        first = false;
      });
    });
  }

  open(service: string): void {
    this.show.emit(this.wb.open({ kind: 'mail', service }));
  }

  openQueues(service: string): void {
    this.show.emit(this.wb.open({ kind: 'queues', service }));
  }

  isQueues(service: string): boolean {
    const s = this.current()?.spec;
    return s?.kind === 'queues' && s.service === service;
  }

  /** "from this machine: worker (2), api": the engine read it from the kernel's socket tables. */
  connectedText(b: BrokerView): string {
    const parts = Object.entries(b.connected).map(([s, n]) => (n > 1 ? `${s} (${n})` : s));
    return parts.length ? `connected: ${parts.join(', ')}` : '';
  }

  isOpen(service: string): boolean {
    const s = this.current()?.spec;
    return s?.kind === 'mail' && s.service === service;
  }
}
