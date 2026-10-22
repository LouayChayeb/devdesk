import { Component, computed, input, signal } from '@angular/core';

/**
 * One measure over time: the latest value large, a 2px line under it, and a hover crosshair that reads any sample.
 * One measure per chart (never two scales on one); the title names it, so no legend.
 */
@Component({
  selector: 'dd-spark',
  template: `
    <div class="head">
      <span class="title">{{ title() }}</span>
      <span class="now mono">{{ shown() }}</span>
    </div>
    <svg [attr.viewBox]="'0 0 ' + W + ' ' + H" preserveAspectRatio="none" (mousemove)="hover($event)" (mouseleave)="at.set(null)" role="img" [attr.aria-label]="title() + ', now ' + fmt()(last())">
      <line x1="0" [attr.y1]="H - 1" [attr.x2]="W" [attr.y2]="H - 1" class="base" />
      @if (path(); as p) {
        <path [attr.d]="p.area" class="area" />
        <path [attr.d]="p.line" class="line" vector-effect="non-scaling-stroke" />
        @if (at() !== null) {
          <line [attr.x1]="p.x(at()!)" y1="0" [attr.x2]="p.x(at()!)" [attr.y2]="H" class="cross" vector-effect="non-scaling-stroke" />
        }
      }
    </svg>
    <div class="foot mono dim">
      <span>{{ at() !== null ? when(at()!) : values().length > 1 ? 'last ' + span() : 'collecting…' }}</span>
      <span>max {{ fmt()(max()) }}</span>
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 4px; padding: 10px 12px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-1); min-width: 0; }
    .head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
    .title { color: var(--text-2); font-size: var(--fs-sm); }
    .now { color: var(--text-1); font-size: 18px; font-weight: 600; }
    svg { width: 100%; height: 56px; display: block; cursor: crosshair; }
    .base { stroke: var(--border); stroke-width: 1; }
    .line { fill: none; stroke: var(--accent); stroke-width: 2; stroke-linejoin: round; }
    .area { fill: var(--accent); opacity: 0.12; }
    .cross { stroke: var(--text-3); stroke-width: 1; }
    .foot { display: flex; justify-content: space-between; font-size: var(--fs-xs); }
  `,
})
export class SparkChart {
  readonly title = input.required<string>();
  readonly values = input.required<number[]>();
  /** When each value was taken (ISO), same length as values. */
  readonly times = input.required<string[]>();
  readonly fmt = input<(n: number) => string>((n) => String(Math.round(n)));
  readonly W = 300;
  readonly H = 56;
  readonly at = signal<number | null>(null);

  readonly last = computed(() => this.values()[this.values().length - 1] ?? 0);
  readonly max = computed(() => Math.max(0, ...this.values()));
  readonly shown = computed(() => this.fmt()(this.at() !== null ? this.values()[this.at()!] : this.last()));
  readonly path = computed(() => {
    const v = this.values();
    if (v.length < 2) return null;
    const top = this.max() || 1;
    const x = (i: number) => (i / (v.length - 1)) * this.W;
    const y = (n: number) => this.H - 2 - (n / top) * (this.H - 6);
    const line = v.map((n, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(n).toFixed(1)}`).join(' ');
    return { line, area: `${line} L${this.W},${this.H - 1} L0,${this.H - 1} Z`, x };
  });

  hover(e: MouseEvent): void {
    const n = this.values().length;
    if (n < 2) return;
    const box = (e.currentTarget as SVGElement).getBoundingClientRect();
    this.at.set(Math.round(((e.clientX - box.left) / box.width) * (n - 1)));
  }

  when(i: number): string {
    const t = this.times()[i];
    return t ? new Date(t).toLocaleTimeString('en-GB', { hour12: false }) : '';
  }

  span(): string {
    const t = this.times();
    const s = Math.round((new Date(t[t.length - 1]).getTime() - new Date(t[0]).getTime()) / 1000);
    return s < 120 ? `${s} s` : `${Math.round(s / 60)} min`;
  }
}
