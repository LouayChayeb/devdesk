import { Component, computed, input, output, signal } from '@angular/core';
import { SetupService } from './engine';
import { Icon } from './icon';

const W = 184;
const H = 58;
const GAP_X = 40;
const GAP_Y = 96;
const PAD = 40;

interface Point {
  x: number;
  y: number;
}

interface Edge {
  id: string;
  from: string;
  to: string;
  condition: string;
  label: string;
  path: string;
  mid: Point;
}

const LABELS: Record<string, string> = { started: 'started', healthy: 'ready', completed_successfully: 'finished OK' };

/**
 * The wizard's dependencies step as a canvas. Services are boxes; "api needs db" is an arrow from api down to db
 * (what needs nothing sits at the bottom). Drawing only: the wizard keeps the draft and the engine checks it.
 */
@Component({
  selector: 'dd-setup-canvas',
  imports: [Icon],
  template: `
    <div class="bar">
      <span class="hint small">
        Drag from a box's <span class="handle-sample"></span> to what it needs · click an arrow to change it (<kbd>Del</kbd>
        removes it) · drag the background to pan · scroll to zoom
      </span>
      <span class="grow"></span>
      <button class="ghost icon sm" title="Zoom out" (click)="zoomBy(1 / 1.2)"><dd-icon name="zoomOut" [size]="15" /></button>
      <span class="mono pct">{{ round(scale() * 100) }}%</span>
      <button class="ghost icon sm" title="Zoom in" (click)="zoomBy(1.2)"><dd-icon name="zoomIn" [size]="15" /></button>
      <button class="sm" title="Put every box back in its place" (click)="tidy()"><dd-icon name="fit" [size]="13" /> Tidy up</button>
    </div>
    @if (message()) {
      <div class="msg small">{{ message() }}</div>
    }
    <div class="canvas" tabindex="0" (keydown)="key($event)" [class.panning]="!!panning">
      <svg width="100%" height="100%" (pointerdown)="panStart($event)" (pointermove)="move($event)" (pointerup)="drop()" (pointerleave)="cancel()" (wheel)="wheel($event)">
        <defs>
          <marker id="needs-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
            <path d="M1,1 L9,5 L1,9 z" class="head" />
          </marker>
          <marker id="needs-arrow-on" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
            <path d="M1,1 L9,5 L1,9 z" class="head on" />
          </marker>
          <pattern id="setup-dots" width="22" height="22" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r="1" class="grid-dot" /></pattern>
        </defs>
        <g [attr.transform]="'translate(' + tx() + ',' + ty() + ') scale(' + scale() + ')'">
          <rect x="-3000" y="-3000" width="8000" height="8000" fill="url(#setup-dots)" />
          @for (e of edges(); track e.id) {
            <g class="edge" [class.on]="selected() === e.id">
              <path class="hit" [attr.d]="e.path" (pointerdown)="$event.stopPropagation()" (click)="select(e.id)" />
              <path class="line" [attr.d]="e.path" [attr.marker-end]="selected() === e.id ? 'url(#needs-arrow-on)' : 'url(#needs-arrow)'" />
            </g>
          }
          @if (linking(); as l) {
            <path class="line linking" [attr.d]="curve(l.from, l.to)" marker-end="url(#needs-arrow-on)" />
          }
          @for (s of services(); track s.name) {
            @if (pos(s.name); as p) {
              <g class="node" [class.target]="hover() === s.name && !!linking() && linking()!.source !== s.name"
                 [attr.transform]="'translate(' + p.x + ',' + p.y + ')'"
                 (pointerdown)="grab($event, s.name)" (pointerenter)="hover.set(s.name)" (pointerleave)="hover.set(null)">
                <rect class="card" [attr.width]="W" [attr.height]="H" rx="10" />
                <rect class="stripe {{ role(s) }}" x="0" y="0" width="4" [attr.height]="H" rx="2" />
                <g class="kind-icon" transform="translate(14, 11)"><path [attr.d]="iconPath(s)" /></g>
                <text class="name" x="38" y="25">{{ s.name }}</text>
                <text class="what" x="14" y="45">{{ subtitle(s) }}</text>
                <circle class="handle" [attr.cx]="W / 2" [attr.cy]="H" r="7" (pointerdown)="startLink($event, s.name)">
                  <title>Drag to what {{ s.name }} needs</title>
                </circle>
              </g>
            }
          }
          <!-- Labels last, so a box never hides one: each is how to change or remove its arrow. -->
          @for (e of edges(); track e.id) {
            <g class="cond" [class.on]="selected() === e.id" [attr.transform]="'translate(' + e.mid.x + ',' + e.mid.y + ')'"
               (pointerdown)="$event.stopPropagation()" (click)="select(e.id)">
              <rect [attr.x]="-e.label.length * 3.4 - 10" y="-10" [attr.width]="e.label.length * 6.8 + 20" height="20" rx="10" />
              <text text-anchor="middle" y="4">{{ e.label }}</text>
            </g>
          }
        </g>
      </svg>

      @if (selectedEdge(); as e) {
        <div class="pop" (pointerdown)="$event.stopPropagation()">
          <div class="small"><span class="mono">{{ e.from }}</span> waits until <span class="mono">{{ e.to }}</span> is</div>
          <div class="segmented">
            @for (c of conditions; track c) {
              <button [class.on]="e.condition === c" (click)="setCondition(e.from, e.to, c)">{{ label(c) }}</button>
            }
          </div>
          <button class="sm danger" (click)="unlink(e.from, e.to)"><dd-icon name="trash" [size]="12" /> Remove</button>
        </div>
      }
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; gap: 8px; height: 100%; min-height: 420px; }
    .bar { display: flex; align-items: center; gap: 6px; }
    .grow { flex: 1; }
    .hint { color: var(--text-2); }
    .pct { font-size: var(--fs-xs); color: var(--text-3); width: 38px; text-align: center; }
    .handle-sample { display: inline-block; width: 9px; height: 9px; border-radius: 999px; background: var(--accent); vertical-align: middle; }
    .msg { color: var(--warn); background: var(--warn-bg); border-left: 2px solid var(--warn); padding: 5px 10px; border-radius: var(--radius-badge); }
    .canvas { position: relative; flex: 1; overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-0); cursor: grab; outline: none; }
    .canvas:focus-visible { border-color: var(--accent-line); }
    .canvas.panning { cursor: grabbing; }
    svg { display: block; touch-action: none; user-select: none; }
    .grid-dot { fill: var(--border); }
    .node { cursor: move; }
    .card { fill: var(--bg-2); stroke: var(--border-strong); }
    .node:hover .card { stroke: var(--border-hover); }
    .node.target .card { stroke: var(--accent); stroke-width: 2; fill: var(--accent-deep); }
    .stripe.data { fill: var(--accent); }
    .stripe.container { fill: var(--info); }
    .stripe.command { fill: var(--text-3); }
    .kind-icon path { fill: none; stroke: var(--text-2); stroke-width: 1.5; stroke-linecap: round; stroke-linejoin: round; }
    .name { fill: var(--text-1); font: 600 13px var(--font-mono); }
    .what { fill: var(--text-3); font: 400 11px var(--font-mono); }
    .handle { fill: var(--accent); stroke: var(--bg-0); stroke-width: 2; cursor: crosshair; }
    .handle:hover { r: 9; }
    .line { fill: none; stroke: var(--text-3); stroke-width: 1.6; pointer-events: none; }
    .hit { fill: none; stroke: transparent; stroke-width: 14; cursor: pointer; }
    .line.linking { stroke: var(--accent); stroke-dasharray: 5 4; }
    .head { fill: var(--text-3); }
    .head.on { fill: var(--accent); }
    .edge.on .line { stroke: var(--accent); stroke-width: 2; }
    .cond { cursor: pointer; }
    .cond rect { fill: var(--bg-3); stroke: var(--border-strong); }
    .cond text { fill: var(--text-2); font: 500 11px var(--font-ui); }
    .cond:hover rect, .cond.on rect { stroke: var(--accent); }
    .cond.on text { fill: var(--text-1); }
    .pop { position: absolute; right: 12px; top: 12px; z-index: 3; display: flex; flex-direction: column; gap: 8px; padding: 10px; background: var(--bg-2); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); }
  `,
})
export class SetupCanvas {
  readonly services = input.required<SetupService[]>();
  /** A service's new "needs" list, for the wizard to store in the draft. */
  readonly needs = output<{ service: string; dependsOn: Record<string, string> }>();

  readonly W = W;
  readonly H = H;
  readonly conditions = ['healthy', 'started', 'completed_successfully'];
  readonly round = Math.round;

  /** Boxes the user dragged; the others keep their computed place. */
  private readonly moved = signal<Record<string, Point>>({});
  readonly scale = signal(1);
  readonly tx = signal(0);
  readonly ty = signal(0);
  readonly linking = signal<{ from: Point; to: Point; source: string } | null>(null);
  readonly hover = signal<string | null>(null);
  readonly selected = signal<string | null>(null);
  readonly message = signal<string | null>(null);
  private dragging: { name: string; dx: number; dy: number } | null = null;
  panning: { x: number; y: number; tx: number; ty: number } | null = null;

  /**
   * Rows by depth: what needs nothing (usually the databases) at the bottom, each service one row above the deepest
   * thing it needs, so arrows point down toward what they wait for.
   */
  private readonly placed = computed(() => {
    const list = this.services();
    const byName = new Map(list.map((s) => [s.name, s]));
    const depth = new Map<string, number>();
    const visit = (name: string, seen: Set<string>): number => {
      if (depth.has(name)) return depth.get(name)!;
      if (seen.has(name)) return 0; // a loop: the engine reports it; here it only needs a place
      seen.add(name);
      const deps = Object.keys(byName.get(name)?.dependsOn ?? {}).filter((d) => byName.has(d));
      const d = deps.length ? 1 + Math.max(...deps.map((x) => visit(x, seen))) : 0;
      depth.set(name, d);
      return d;
    };
    list.forEach((s) => visit(s.name, new Set()));
    const max = Math.max(0, ...depth.values());
    const rows: string[][] = Array.from({ length: max + 1 }, () => []);
    list.forEach((s) => rows[depth.get(s.name) ?? 0].push(s.name));
    const widest = Math.max(...rows.map((r) => r.length));
    const out: Record<string, Point> = {};
    rows.forEach((row, d) => {
      const offset = ((widest - row.length) * (W + GAP_X)) / 2; // centred under the widest row
      row.forEach((name, i) => (out[name] = { x: PAD + offset + i * (W + GAP_X), y: PAD + (max - d) * (H + GAP_Y) }));
    });
    return out;
  });

  pos(name: string): Point | undefined {
    return this.moved()[name] ?? this.placed()[name];
  }

  readonly edges = computed<Edge[]>(() => {
    const out: Edge[] = [];
    for (const s of this.services()) {
      for (const [to, condition] of Object.entries(s.dependsOn)) {
        const a = this.pos(s.name);
        const b = this.pos(to);
        if (!a || !b) continue;
        const from = { x: a.x + W / 2, y: a.y + H };
        const end = { x: b.x + W / 2, y: b.y };
        out.push({
          id: s.name + '>' + to,
          from: s.name,
          to,
          condition,
          label: this.label(condition),
          path: this.curve(from, end),
          mid: this.along(from, end, 0.72),
        });
      }
    }
    return out;
  });

  readonly selectedEdge = computed(() => this.edges().find((e) => e.id === this.selected()) ?? null);

  label(condition: string): string {
    return LABELS[condition] ?? condition;
  }

  role(s: SetupService): 'data' | 'container' | 'command' {
    return s.dataStore ? 'data' : s.kind;
  }

  subtitle(s: SetupService): string {
    const text = s.kind === 'container' ? s.image : s.command || '(no command yet)';
    return text.length > 24 ? text.slice(0, 23) + '…' : text;
  }

  /** 16px line icons: a cylinder for databases, a box for containers, a prompt for commands. */
  iconPath(s: SetupService): string {
    switch (this.role(s)) {
      case 'data':
        return 'M2,4 C2,1 14,1 14,4 C14,7 2,7 2,4 Z M2,4 L2,12 C2,15 14,15 14,12 L14,4 M2,8 C2,11 14,11 14,8';
      case 'container':
        return 'M8,1 L15,4.5 L15,11.5 L8,15 L1,11.5 L1,4.5 Z M1,4.5 L8,8 L15,4.5 M8,8 L8,15';
      default:
        return 'M2,4 L7,8 L2,12 M9,13 L14,13';
    }
  }

  curve(a: Point, b: Point): string {
    const bend = Math.max(36, Math.abs(b.y - a.y) / 2);
    return `M${a.x},${a.y} C${a.x},${a.y + bend} ${b.x},${b.y - bend} ${b.x},${b.y}`;
  }

  /** A point on {@link curve} at {@code t} (0 = start, 1 = end): labels sit near the arrow's end, past the boxes. */
  private along(a: Point, b: Point, t: number): Point {
    const bend = Math.max(36, Math.abs(b.y - a.y) / 2);
    const c1 = { x: a.x, y: a.y + bend };
    const c2 = { x: b.x, y: b.y - bend };
    const u = 1 - t;
    return {
      x: u * u * u * a.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * b.x,
      y: u * u * u * a.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * b.y,
    };
  }

  // ---- pointer handling (screen → canvas: undo the pan and zoom) ----

  private point(e: PointerEvent | WheelEvent): Point {
    const box = (e.currentTarget as Element).closest('svg')!.getBoundingClientRect();
    return { x: (e.clientX - box.left - this.tx()) / this.scale(), y: (e.clientY - box.top - this.ty()) / this.scale() };
  }

  panStart(e: PointerEvent): void {
    this.selected.set(null);
    this.panning = { x: e.clientX, y: e.clientY, tx: this.tx(), ty: this.ty() };
  }

  grab(e: PointerEvent, name: string): void {
    e.stopPropagation(); // a box, not the background
    const p = this.point(e);
    const at = this.pos(name)!;
    this.dragging = { name, dx: p.x - at.x, dy: p.y - at.y };
    this.selected.set(null);
  }

  startLink(e: PointerEvent, name: string): void {
    e.stopPropagation(); // a new arrow, not a box drag
    const at = this.pos(name)!;
    const from = { x: at.x + W / 2, y: at.y + H };
    this.linking.set({ from, to: from, source: name });
    this.selected.set(null);
    this.message.set(null);
  }

  move(e: PointerEvent): void {
    const link = this.linking();
    if (link) {
      this.linking.set({ ...link, to: this.point(e) });
    } else if (this.dragging) {
      const p = this.point(e);
      const { name, dx, dy } = this.dragging;
      this.moved.update((m) => ({ ...m, [name]: { x: p.x - dx, y: p.y - dy } }));
    } else if (this.panning) {
      this.tx.set(this.panning.tx + e.clientX - this.panning.x);
      this.ty.set(this.panning.ty + e.clientY - this.panning.y);
    }
  }

  drop(): void {
    const link = this.linking();
    const target = this.hover();
    this.cancel();
    if (!link || !target || target === link.source) return;
    const s = this.services().find((x) => x.name === link.source)!;
    if (target in s.dependsOn) return;
    if (this.reaches(target, link.source)) {
      this.message.set(`That would make a loop: ${target} already waits for ${link.source}, so neither could ever start.`);
      return;
    }
    const t = this.services().find((x) => x.name === target)!;
    // Databases are worth waiting for until they answer; other services just until they've started.
    const condition = t.dataStore ? 'healthy' : 'started';
    this.needs.emit({ service: s.name, dependsOn: { ...s.dependsOn, [target]: condition } });
    this.selected.set(s.name + '>' + target);
  }

  cancel(): void {
    this.dragging = null;
    this.panning = null;
    this.linking.set(null);
  }

  wheel(e: WheelEvent): void {
    e.preventDefault();
    const box = (e.currentTarget as Element).getBoundingClientRect();
    this.zoomAt(e.deltaY < 0 ? 1.1 : 1 / 1.1, e.clientX - box.left, e.clientY - box.top);
  }

  zoomBy(factor: number): void {
    this.zoomAt(factor, 300, 200);
  }

  /** Zooms keeping the point under the pointer still. */
  private zoomAt(factor: number, sx: number, sy: number): void {
    const next = Math.min(2, Math.max(0.4, this.scale() * factor));
    const k = next / this.scale();
    this.tx.set(sx - (sx - this.tx()) * k);
    this.ty.set(sy - (sy - this.ty()) * k);
    this.scale.set(next);
  }

  tidy(): void {
    this.moved.set({});
    this.scale.set(1);
    this.tx.set(0);
    this.ty.set(0);
    this.message.set(null);
  }

  select(id: string): void {
    this.selected.set(id);
  }

  key(e: KeyboardEvent): void {
    const edge = this.selectedEdge();
    if (edge && (e.key === 'Delete' || e.key === 'Backspace')) {
      e.preventDefault();
      this.unlink(edge.from, edge.to);
    } else if (e.key === 'Escape') {
      this.selected.set(null);
    }
  }

  /** Whether {@code from} already waits for {@code to}, directly or through others. */
  private reaches(from: string, to: string): boolean {
    const byName = new Map(this.services().map((s) => [s.name, s]));
    const seen = new Set<string>();
    const stack = [from];
    while (stack.length) {
      const n = stack.pop()!;
      if (n === to) return true;
      if (seen.has(n)) continue;
      seen.add(n);
      stack.push(...Object.keys(byName.get(n)?.dependsOn ?? {}));
    }
    return false;
  }

  setCondition(from: string, to: string, condition: string): void {
    const s = this.services().find((x) => x.name === from)!;
    this.needs.emit({ service: from, dependsOn: { ...s.dependsOn, [to]: condition } });
  }

  unlink(from: string, to: string): void {
    const s = this.services().find((x) => x.name === from)!;
    const { [to]: _gone, ...rest } = s.dependsOn;
    this.needs.emit({ service: from, dependsOn: rest });
    this.selected.set(null);
  }
}
