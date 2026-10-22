import { AfterViewInit, Component, ElementRef, OnDestroy, computed, inject, output, signal, viewChild } from '@angular/core';
import { Engine, ServiceView } from './engine';
import { Icon } from './icon';
import { StatusDot } from './status-dot';
import { bytes, cpu, tone } from './format';

const W = 208;
const H = 66;
const GAP_X = 48;
const GAP_Y = 84;
const PAD = 40;

type Mode = 'chain' | 'impact';

interface Node {
  s: ServiceView;
  x: number;
  y: number;
  tone: string;
}

interface Edge {
  id: string;
  from: string;
  to: string;
  path: string;
  mid: { x: number; y: number };
  condition: string;
  tone: string;
  flowing: boolean;
}

/**
 * DESIGN.md §4.5: the dependency graph. Rows, "needs" and "impact" come from the engine; everything here is drawing
 * (positions, pan and zoom).
 */
@Component({
  selector: 'dd-graph',
  imports: [Icon, StatusDot],
  template: `
    <div class="toolbar">
      <div class="segmented" role="radiogroup" aria-label="Highlight">
        <button [class.on]="mode() === 'chain'" (click)="mode.set('chain')" title="What it needs and what needs it">Dependencies</button>
        <button [class.on]="mode() === 'impact'" (click)="mode.set('impact')" title="Everything that breaks if it stops">Impact if it stops</button>
      </div>
      <span class="hint">
        @if (picked()) { <kbd>Esc</kbd> to clear } @else { Click a service · double-click for details · drag to pan · scroll to zoom }
      </span>
      <span class="grow"></span>
      <div class="legend">
        <span><i class="ln ok"></i>ready</span>
        <span><i class="ln warn"></i>waiting</span>
        <span><i class="ln err"></i>broken</span>
      </div>
      <div class="zoom">
        <button class="ghost icon sm" title="Zoom out" (click)="zoomBy(1 / 1.2)"><dd-icon name="zoomOut" [size]="15" /></button>
        <span class="mono pct">{{ round(scale() * 100) }}%</span>
        <button class="ghost icon sm" title="Zoom in" (click)="zoomBy(1.2)"><dd-icon name="zoomIn" [size]="15" /></button>
        <button class="ghost icon sm" title="Fit to screen (F)" (click)="fit()"><dd-icon name="fit" [size]="15" /></button>
      </div>
    </div>

    <div class="canvas" #canvas [class.panning]="panning()" (pointerdown)="panStart($event)" (wheel)="wheel($event)" (click)="background()">
      @if (layout(); as g) {
        <svg width="100%" height="100%">
          <defs>
            @for (t of ['idle', 'ok', 'warn', 'err', 'hot']; track t) {
              <marker [attr.id]="'arrow-' + t" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
                <path d="M1,1 L9,5 L1,9 z" [attr.class]="'head ' + t" />
              </marker>
            }
            <pattern id="dots" width="22" height="22" patternUnits="userSpaceOnUse">
              <circle cx="1" cy="1" r="1" class="grid-dot" />
            </pattern>
          </defs>
          <g [attr.transform]="'translate(' + tx() + ',' + ty() + ') scale(' + scale() + ')'">
            <rect class="grid" [attr.x]="-2000" [attr.y]="-2000" [attr.width]="g.width + 4000" [attr.height]="g.height + 4000" fill="url(#dots)" />
            @for (e of g.edges; track e.id) {
              <g class="edge {{ e.tone }}" [class.dim]="dimEdge(e)" [class.hot]="hotEdge(e)">
                <path class="line" [attr.d]="e.path" [attr.marker-end]="'url(#arrow-' + (hotEdge(e) ? 'hot' : e.tone) + ')'" />
                @if (e.flowing) { <path class="flow" [attr.d]="e.path" /> }
                <g class="cond" [attr.transform]="'translate(' + e.mid.x + ',' + e.mid.y + ')'">
                  <rect [attr.x]="-e.condition.length * 3.2 - 7" y="-9" [attr.width]="e.condition.length * 6.4 + 14" height="18" rx="9" />
                  <text text-anchor="middle" y="4">{{ e.condition }}</text>
                </g>
              </g>
            }
            @for (n of g.nodes; track n.s.name) {
              <g
                class="node {{ n.tone }}"
                [class.dim]="dimNode(n.s.name)"
                [class.picked]="picked() === n.s.name"
                [class.hit]="mode() === 'impact' && inImpact(n.s.name)"
                [class.blocked]="n.s.state.label === 'blocked'"
                [attr.transform]="'translate(' + n.x + ',' + n.y + ')'"
                (click)="pick(n.s.name, $event)"
                (dblclick)="details.emit(n.s.name)"
                (pointerdown)="$event.stopPropagation()"
              >
                <rect class="card" [attr.width]="W" [attr.height]="H" rx="10" />
                <rect class="bar" x="0" y="0" width="4" [attr.height]="H" rx="2" />
                <circle class="dot" cx="20" cy="22" r="4.5" />
                <text class="name" x="33" y="26">{{ n.s.name }}</text>
                <text class="kind" [attr.x]="W - 12" y="26" text-anchor="end">{{ n.s.kind === 'container' ? 'container' : 'process' }}</text>
                <text class="meta" x="16" y="48">
                  <tspan class="state">{{ n.s.state.label }}</tspan>
                  @if (n.s.ports.length) { <tspan class="sep"> · </tspan><tspan>:{{ n.s.ports.join(' :') }}</tspan> }
                  @if (usage(n.s); as u) { <tspan class="sep"> · </tspan><tspan>{{ u }}</tspan> }
                </text>
              </g>
            }
          </g>
        </svg>
      }

      @if (pickedService(); as s) {
        <aside class="side" (click)="$event.stopPropagation()" (pointerdown)="$event.stopPropagation()">
          <header>
            <dd-dot [label]="s.state.label" />
            <span class="title">{{ s.name }}</span>
            <span class="grow"></span>
            <button class="ghost icon sm" title="Clear (Esc)" (click)="picked.set(null)"><dd-icon name="close" [size]="14" /></button>
          </header>
          <div class="state-line"><span class="pill {{ s.state.label }}">{{ s.state.label }}</span></div>
          @if (s.state.detail) { <p class="detail">{{ s.state.detail }}</p> }
          <div class="btns">
            @if (isActive(s)) {
              <button class="sm" (click)="action.emit({ service: s.name, action: 'restart' })"><dd-icon name="restart" [size]="13" /> Restart</button>
              <button class="sm danger" (click)="action.emit({ service: s.name, action: 'stop' })"><dd-icon name="stop" [size]="10" /> Stop</button>
            } @else {
              <button class="sm primary" (click)="action.emit({ service: s.name, action: 'start' })"><dd-icon name="play" [size]="11" /> Start</button>
            }
            <button class="sm" (click)="details.emit(s.name)">Details</button>
            <button class="sm ghost" (click)="logs.emit(s.name)">Logs</button>
          </div>
          <div class="block-label">Needs</div>
          <div class="chips">
            @for (n of reachOf(s.name).needs; track n) { <button class="chip" (click)="pick(n)">{{ n }}</button> } @empty { <span class="none">nothing</span> }
          </div>
          <div class="block-label">If it stops, these break</div>
          <div class="chips">
            @for (n of reachOf(s.name).impact; track n) { <button class="chip warn" (click)="pick(n)">{{ n }}</button> } @empty { <span class="none">nothing: no service needs it</span> }
          </div>
        </aside>
      }
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; height: 100%; min-height: 320px; }
    .toolbar { display: flex; align-items: center; gap: 14px; padding: 8px 14px; border-bottom: 1px solid var(--border); }
    .hint { color: var(--text-3); font-size: var(--fs-sm); }
    .grow { flex: 1; }
    .legend { display: flex; gap: 12px; color: var(--text-3); font-size: var(--fs-xs); }
    .legend span { display: inline-flex; align-items: center; gap: 5px; }
    .ln { display: inline-block; width: 16px; height: 0; border-top: 2px solid var(--border-strong); }
    .ln.ok { border-color: var(--ok); }
    .ln.warn { border-color: var(--warn); border-top-style: dashed; }
    .ln.err { border-color: var(--err); }
    .zoom { display: flex; align-items: center; gap: 2px; background: var(--bg-1); border: 1px solid var(--border); border-radius: var(--radius); padding: 1px; }
    .pct { width: 42px; text-align: center; color: var(--text-2); font-size: var(--fs-xs); }

    .canvas { position: relative; flex: 1; min-height: 0; overflow: hidden; cursor: grab; background: radial-gradient(ellipse at 50% 0%, rgba(76, 201, 176, 0.05), transparent 60%); }
    .canvas.panning { cursor: grabbing; }
    svg { display: block; user-select: none; }
    .grid-dot { fill: #22282f; }

    .edge .line { fill: none; stroke: var(--border-strong); stroke-width: 1.6; transition: stroke var(--ease), opacity 160ms; }
    .edge.ok .line { stroke: rgba(63, 185, 80, 0.55); }
    .edge.warn .line { stroke: var(--warn); stroke-dasharray: 5 4; }
    .edge.err .line { stroke: var(--err); }
    .edge.hot .line { stroke: var(--accent); stroke-width: 2.2; }
    .edge .flow { fill: none; stroke: var(--ok); stroke-width: 2; stroke-dasharray: 2 14; stroke-linecap: round; opacity: 0.8; animation: flow 1.6s linear infinite; pointer-events: none; }
    .edge.hot .flow { stroke: var(--accent-hover); }
    @keyframes flow { to { stroke-dashoffset: -32; } }
    .head { fill: var(--border-strong); }
    .head.ok { fill: rgba(63, 185, 80, 0.75); }
    .head.warn { fill: var(--warn); }
    .head.err { fill: var(--err); }
    .head.hot { fill: var(--accent); }
    .cond rect { fill: var(--bg-0); stroke: var(--border); }
    .cond text { fill: var(--text-3); font: 500 10px var(--font-mono); }
    .edge.hot .cond rect { stroke: var(--accent); }
    .edge.hot .cond text { fill: var(--text-1); }
    .dim { opacity: 0.22; }
    .edge, .node { transition: opacity 180ms ease-out; }

    .node { cursor: pointer; }
    .node .card { fill: var(--bg-1); stroke: var(--border-strong); stroke-width: 1; filter: drop-shadow(0 4px 10px rgba(0, 0, 0, 0.35)); transition: stroke var(--ease), fill var(--ease); }
    .node:hover .card { fill: var(--bg-2); stroke: var(--border-hover); }
    .node.picked .card { stroke: var(--accent); stroke-width: 2; fill: #13241f; }
    .node.hit .card { stroke: var(--warn); stroke-width: 2; }
    .node.err .card, .node.err-x .card { stroke: rgba(248, 81, 73, 0.75); }
    .node.blocked .card { stroke: var(--warn); stroke-dasharray: 6 4; }
    .node .bar { fill: var(--idle); }
    .node.ok .bar, .node.ok-ring .bar { fill: var(--ok); }
    .node.warn .bar, .node.warn-ring .bar { fill: var(--warn); }
    .node.err .bar, .node.err-x .bar { fill: var(--err); }
    .node .dot { fill: var(--idle); }
    .node.ok .dot, .node.ok-ring .dot { fill: var(--ok); }
    .node.warn .dot { fill: var(--warn); animation: pulse 1.4s ease-in-out infinite; }
    .node.warn-ring .dot { fill: var(--warn); }
    .node.err .dot, .node.err-x .dot { fill: var(--err); }
    @keyframes pulse { 50% { opacity: 0.35; } }
    .name { fill: var(--text-1); font: 600 14px var(--font-ui); }
    .kind { fill: var(--text-3); font: 400 10px var(--font-ui); text-transform: uppercase; letter-spacing: 0.06em; }
    .meta { fill: var(--text-2); font: 400 11px var(--font-mono); }
    .meta .sep { fill: var(--text-3); }
    .node.ok .state, .node.ok-ring .state { fill: var(--ok); }
    .node.warn .state, .node.warn-ring .state { fill: var(--warn); }
    .node.err .state, .node.err-x .state { fill: var(--err); }

    .side { position: absolute; top: 12px; right: 12px; width: 300px; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: 10px; box-shadow: var(--shadow-float); padding: 12px 14px; cursor: default; animation: slide-in 180ms ease-out; }
    .side header { display: flex; align-items: center; gap: 8px; }
    .side .title { font: 600 var(--fs-lg) var(--font-ui); }
    .state-line { margin-top: 8px; }
    .pill { display: inline-flex; align-items: center; height: 20px; padding: 0 8px; border-radius: 999px; font-size: var(--fs-sm); background: var(--bg-2); color: var(--text-2); }
    .pill.healthy, .pill.running, .pill.completed { color: var(--ok); background: var(--ok-bg); }
    .pill.starting, .pill.waiting, .pill.blocked, .pill.stopping { color: var(--warn); background: var(--warn-bg); }
    .pill.unhealthy, .pill.crashed, .pill.crash-loop, .pill.failed { color: var(--err); background: var(--err-bg); }
    .detail { color: var(--text-2); font-size: var(--fs-sm); margin: 8px 0 0; }
    .btns { display: flex; flex-wrap: wrap; gap: 6px; margin: 12px 0 4px; }
    .block-label { font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin: 12px 0 6px; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .chip { height: 24px; padding: 0 10px; border-radius: 999px; font: 500 var(--fs-sm) var(--font-mono); background: var(--bg-2); }
    .chip.warn { color: var(--warn); border-color: rgba(210, 153, 34, 0.4); background: var(--warn-bg); }
    .none { color: var(--text-3); font-size: var(--fs-sm); }
  `,
  host: { '(document:keydown)': 'key($event)' },
})
export class GraphPanel implements AfterViewInit, OnDestroy {
  readonly engine = inject(Engine);
  readonly details = output<string>();
  readonly logs = output<string>();
  readonly action = output<{ service: string; action: 'start' | 'stop' | 'restart' }>();
  readonly mode = signal<Mode>('chain');
  readonly picked = signal<string | null>(null);
  readonly scale = signal(1);
  readonly tx = signal(0);
  readonly ty = signal(0);
  readonly panning = signal(false);
  readonly W = W;
  readonly H = H;
  readonly round = Math.round;
  private readonly canvas = viewChild.required<ElementRef<HTMLDivElement>>('canvas');
  private resize?: ResizeObserver;
  private fittedFor = '';

  /**
   * Frontends on top, databases at the bottom: the engine's start levels drawn last-first. Inside a row, services sit
   * under the average position of what points at them, which keeps lines from crossing.
   */
  readonly layout = computed(() => {
    const p = this.engine.project();
    if (!p?.levels?.length) return null;
    const byName = new Map(p.services.map((s) => [s.name, s]));
    const rows = [...p.levels].reverse().map((r) => [...r]);
    const order = new Map<string, number>();
    rows.forEach((r) => r.forEach((n, i) => order.set(n, i)));
    const dependents = (n: string) => p.services.filter((s) => s.dependsOn.some((d) => d.service === n)).map((s) => s.name);
    const deps = (n: string) => byName.get(n)?.dependsOn.map((d) => d.service) ?? [];
    const sortBy = (row: string[], neighbours: (n: string) => string[]) => {
      const score = (n: string) => {
        const ns = neighbours(n).filter((x) => order.has(x));
        return ns.length ? ns.reduce((a, x) => a + order.get(x)!, 0) / ns.length : order.get(n)!;
      };
      row.sort((a, b) => score(a) - score(b));
      row.forEach((n, i) => order.set(n, i));
    };
    for (let pass = 0; pass < 2; pass++) {
      for (let r = 1; r < rows.length; r++) sortBy(rows[r], dependents);
      for (let r = rows.length - 2; r >= 0; r--) sortBy(rows[r], deps);
    }
    const widest = Math.max(...rows.map((r) => r.length), 1);
    const width = widest * (W + GAP_X) - GAP_X + PAD * 2;
    const pos = new Map<string, { x: number; y: number }>();
    rows.forEach((row, r) => {
      const offset = (width - (row.length * (W + GAP_X) - GAP_X)) / 2;
      row.forEach((name, c) => pos.set(name, { x: offset + c * (W + GAP_X), y: PAD + r * (H + GAP_Y) }));
    });
    const nodes: Node[] = p.services.map((s) => ({ s, ...pos.get(s.name)!, tone: tone(s.state.label) }));
    // Several lines leaving or entering one card get their own spot along its edge, ordered left to right, so they
    // never meet in a single point.
    const spot = (index: number, count: number) => (count === 1 ? W / 2 : W * 0.28 + (W * 0.44 * index) / (count - 1));
    const outOf = new Map<string, string[]>();
    const into = new Map<string, string[]>();
    p.services.forEach((s) =>
      s.dependsOn.forEach((d) => {
        outOf.set(s.name, [...(outOf.get(s.name) ?? []), d.service]);
        into.set(d.service, [...(into.get(d.service) ?? []), s.name]);
      }),
    );
    outOf.forEach((list) => list.sort((a, b) => pos.get(a)!.x - pos.get(b)!.x));
    into.forEach((list) => list.sort((a, b) => pos.get(a)!.x - pos.get(b)!.x));
    const edges: Edge[] = p.services.flatMap((s) =>
      s.dependsOn.map((d) => {
        const a = pos.get(s.name)!;
        const b = pos.get(d.service)!;
        const outs = outOf.get(s.name)!;
        const ins = into.get(d.service)!;
        const x1 = a.x + spot(outs.indexOf(d.service), outs.length);
        const y1 = a.y + H;
        const x2 = b.x + spot(ins.indexOf(s.name), ins.length);
        const y2 = b.y - 3;
        const bend = Math.max(36, (y2 - y1) * 0.5);
        const dep = byName.get(d.service)!;
        const t = tone(dep.state.label);
        const edgeTone = t.startsWith('ok') ? 'ok' : t.startsWith('warn') ? 'warn' : t.startsWith('err') ? 'err' : 'idle';
        return {
          id: `${s.name}>${d.service}`,
          from: s.name,
          to: d.service,
          path: `M${x1},${y1} C${x1},${y1 + bend} ${x2},${y2 - bend} ${x2},${y2}`,
          // The condition is about the dependent's wait, so it sits just under the dependent's card.
          mid: { x: x1 + (x2 - x1) * 0.12, y: y1 + 20 },
          condition: d.condition.replace(/_/g, ' '),
          tone: edgeTone,
          flowing: edgeTone === 'ok' && tone(s.state.label).startsWith('ok'),
        };
      }),
    );
    return { nodes, edges, width, height: rows.length * (H + GAP_Y) - GAP_Y + PAD * 2, key: p.root + rows.flat().join() };
  });

  readonly pickedService = computed(() => this.engine.project()?.services.find((s) => s.name === this.picked()) ?? null);

  ngAfterViewInit(): void {
    this.resize = new ResizeObserver(() => {
      const g = this.layout();
      if (g && this.fittedFor !== g.key) this.fit();
    });
    this.resize.observe(this.canvas().nativeElement);
    queueMicrotask(() => this.fit());
  }

  ngOnDestroy(): void {
    this.resize?.disconnect();
  }

  reachOf(name: string): { needs: string[]; impact: string[] } {
    return this.engine.project()?.reach?.[name] ?? { needs: [], impact: [] };
  }

  inImpact(name: string): boolean {
    const p = this.picked();
    return !!p && this.reachOf(p).impact.includes(name);
  }

  /** Visible while something is picked: the picked service plus its chain (or its impact). */
  private related(name: string): boolean {
    const p = this.picked();
    if (!p || name === p) return true;
    const r = this.reachOf(p);
    return this.mode() === 'impact' ? r.impact.includes(name) : r.needs.includes(name) || r.impact.includes(name);
  }

  dimNode(name: string): boolean {
    return !this.related(name);
  }

  dimEdge(e: Edge): boolean {
    return !!this.picked() && !(this.related(e.from) && this.related(e.to));
  }

  hotEdge(e: Edge): boolean {
    return !!this.picked() && this.related(e.from) && this.related(e.to);
  }

  usage(s: ServiceView): string | null {
    const r = this.engine.resources()[s.name];
    if (!r || !['running', 'healthy', 'unhealthy'].includes(s.state.label)) return null;
    return `${cpu(r.cpu)} ${bytes(r.memory)}`;
  }

  isActive(s: ServiceView): boolean {
    return ['running', 'healthy', 'unhealthy', 'starting', 'waiting', 'stopping'].includes(s.state.label);
  }

  pick(name: string, e?: Event): void {
    e?.stopPropagation();
    this.picked.set(this.picked() === name && e ? null : name);
  }

  background(): void {
    if (!this.moved) this.picked.set(null);
  }

  key(e: KeyboardEvent): void {
    if ((e.target as HTMLElement)?.closest?.('input, textarea, select')) return;
    if (e.key === 'Escape') this.picked.set(null);
    if (e.key === 'f' || e.key === 'F') this.fit();
  }

  // ---- pan and zoom (drawing only) ----

  private moved = false;

  panStart(e: PointerEvent): void {
    if (e.button !== 0) return;
    const startX = e.clientX;
    const startY = e.clientY;
    const tx0 = this.tx();
    const ty0 = this.ty();
    this.moved = false;
    const move = (m: PointerEvent) => {
      if (Math.abs(m.clientX - startX) + Math.abs(m.clientY - startY) > 3) {
        this.moved = true;
        this.panning.set(true);
      }
      this.tx.set(tx0 + m.clientX - startX);
      this.ty.set(ty0 + m.clientY - startY);
    };
    const up = () => {
      this.panning.set(false);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      setTimeout(() => (this.moved = false));
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  wheel(e: WheelEvent): void {
    e.preventDefault();
    const rect = this.canvas().nativeElement.getBoundingClientRect();
    this.zoomAt(Math.exp(-e.deltaY * 0.0015), e.clientX - rect.left, e.clientY - rect.top);
  }

  zoomBy(f: number): void {
    const rect = this.canvas().nativeElement.getBoundingClientRect();
    this.zoomAt(f, rect.width / 2, rect.height / 2);
  }

  private zoomAt(f: number, cx: number, cy: number): void {
    const next = Math.min(2.5, Math.max(0.3, this.scale() * f));
    const k = next / this.scale();
    this.tx.set(cx - (cx - this.tx()) * k);
    this.ty.set(cy - (cy - this.ty()) * k);
    this.scale.set(next);
  }

  /** Whole graph in view, never larger than 100%. */
  fit(): void {
    const g = this.layout();
    const el = this.canvas()?.nativeElement;
    if (!g || !el || el.clientWidth === 0) return;
    const s = Math.min(1, el.clientWidth / g.width, el.clientHeight / g.height);
    this.scale.set(s);
    this.tx.set((el.clientWidth - g.width * s) / 2);
    this.ty.set(Math.max(0, (el.clientHeight - g.height * s) / 2));
    this.fittedFor = g.key;
  }
}
