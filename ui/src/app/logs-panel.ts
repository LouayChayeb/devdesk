import { Component, ElementRef, computed, effect, inject, input, output, signal, viewChild } from '@angular/core';
import { CdkVirtualScrollViewport, ScrollingModule } from '@angular/cdk/scrolling';
import { Engine, LogLine } from './engine';
import { Segment, ansi, time } from './format';

const ERROR_WORDS = /\b(error|exception|fatal|panic|traceback)\b/i;

/** DESIGN.md §4.8: virtual scrolling, follows the tail, pauses when scrolled up, stderr marked. */
@Component({
  selector: 'dd-logs',
  imports: [ScrollingModule],
  template: `
    <div class="bar">
      <span class="label">Logs</span>
      <select [value]="service() ?? ''" (change)="serviceChange.emit($any($event.target).value || null)">
        <option value="">All services</option>
        @for (s of engine.project()?.services ?? []; track s.name) {
          <option [value]="s.name">{{ s.name }}</option>
        }
      </select>
      <input #search placeholder="Filter…  ( / )" [value]="filter()" (input)="filter.set($any($event.target).value)" />
      <button class="ghost" [class.on]="errorsOnly()" (click)="errorsOnly.set(!errorsOnly())">Errors only</button>
      <button class="ghost" (click)="clearedAt.set(lastAt())">Clear view</button>
      <span class="count mono">{{ shown().length }} lines</span>
    </div>
    <cdk-virtual-scroll-viewport class="view mono" itemSize="20" (scrolledIndexChange)="onScroll()">
      <div *cdkVirtualFor="let l of shown(); trackBy: track" class="line" [class.err]="l.stream === 'stderr'">
        <span class="t">{{ t(l.at) }}</span>
        @if (!service()) {
          <span class="svc">{{ l.service }}</span>
        }
        <span class="txt">@for (s of seg(l.text); track $index) {<span [class]="s.cls">{{ s.text }}</span>}</span>
      </div>
    </cdk-virtual-scroll-viewport>
    @if (!follow() && unseen() > 0) {
      <button class="pill" (click)="toBottom()">↓ {{ unseen() }} new lines</button>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; position: relative; background: var(--bg-0); }
    .bar { display: flex; align-items: center; gap: 8px; padding: 6px 12px; border-bottom: 1px solid var(--border); background: var(--bg-1); }
    .label { font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin-right: 4px; }
    input { width: 200px; }
    .on { color: var(--err); background: var(--err-bg); }
    .count { margin-left: auto; color: var(--text-3); font-size: var(--fs-xs); }
    .view { flex: 1 1 0; min-height: 0; font-size: var(--fs-md); line-height: 20px; }
    .line { height: 20px; padding: 0 12px; white-space: pre; overflow: hidden; text-overflow: ellipsis; border-left: 2px solid transparent; }
    .line.err { border-left-color: var(--err); }
    .t { color: var(--text-3); margin-right: 12px; }
    .svc { color: var(--accent); margin-right: 12px; display: inline-block; min-width: 72px; }
    .pill { position: absolute; right: 24px; bottom: 16px; background: var(--bg-3); border-color: var(--border-strong); box-shadow: var(--shadow-float); }
    .b { font-weight: 600; }
    .a30, .a90 { color: var(--text-3); } .a31, .a91 { color: var(--err); } .a32, .a92 { color: var(--ok); }
    .a33, .a93 { color: var(--warn); } .a34, .a94 { color: var(--accent); } .a35, .a95 { color: #c297ff; }
    .a36, .a96 { color: var(--info); } .a37, .a97 { color: var(--text-1); }
  `,
  host: { '(document:keydown./)': 'focusSearch($event)' },
})
export class LogsPanel {
  readonly engine = inject(Engine);
  readonly service = input<string | null>(null);
  readonly serviceChange = output<string | null>();
  readonly filter = signal('');
  readonly errorsOnly = signal(false);
  /** Timestamp of the last line hidden by "Clear view" (the stored log stays complete). */
  readonly clearedAt = signal('');
  readonly follow = signal(true);
  readonly seenCount = signal(0);
  private readonly viewport = viewChild.required(CdkVirtualScrollViewport);
  private readonly search = viewChild.required<ElementRef<HTMLInputElement>>('search');

  readonly shown = computed(() => {
    const svc = this.service();
    const f = this.filter().toLowerCase();
    const errorsOnly = this.errorsOnly();
    return this.engine
      .lines()
      .filter(
        (l) => l.at > this.clearedAt(),
      )
      .filter(
        (l) =>
          (!svc || l.service === svc) &&
          (!f || l.text.toLowerCase().includes(f)) &&
          (!errorsOnly || l.stream === 'stderr' || ERROR_WORDS.test(l.text)),
      );
  });
  readonly unseen = computed(() => Math.max(0, this.shown().length - this.seenCount()));

  private previousLength = 0;

  constructor() {
    effect(() => {
      const n = this.shown().length;
      if (n < this.previousLength) {
        // The list got shorter (new project, filter, clear): start over at the newest lines.
        this.follow.set(true);
      }
      this.previousLength = n;
      if (this.follow()) {
        // Follow the tail while the user is at the bottom. scrollTo bottom also works when the list shrank.
        queueMicrotask(() => {
          this.viewport().checkViewportSize();
          this.viewport().scrollTo({ bottom: 0 });
        });
        this.seenCount.set(n);
      }
    });
  }

  lastAt(): string {
    const lines = this.engine.lines();
    return lines.length ? lines[lines.length - 1].at : '';
  }

  onScroll(): void {
    const v = this.viewport();
    const atBottom = v.measureScrollOffset('bottom') < 40;
    this.follow.set(atBottom);
    if (atBottom) this.seenCount.set(this.shown().length);
  }

  toBottom(): void {
    this.follow.set(true);
    this.viewport().scrollTo({ bottom: 0 });
  }

  focusSearch(e: Event): void {
    if ((e.target as HTMLElement).tagName !== 'INPUT') {
      e.preventDefault();
      this.search().nativeElement.focus();
    }
  }

  track = (i: number, l: LogLine) => i;
  t = time;
  seg = (text: string): Segment[] => ansi(text);
}
