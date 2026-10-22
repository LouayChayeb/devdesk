import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { DiagramView as Diagram, Engine } from './engine';
import { Doc, Workbench } from './workbench';
import { Icon } from './icon';

const W = 230;
const HEAD = 30;
const ROW = 19;
const MAX_COLS = 14;
const GAP_X = 56;
const GAP_Y = 70;
const PAD = 30;

interface Box {
  id: string;
  name: string;
  schema: string;
  rows: number;
  x: number;
  y: number;
  h: number;
  columns: { name: string; type: string; primaryKey: boolean; link: boolean; nullable: boolean }[];
  more: number;
  outside: boolean;
}

/** One diagram tab: the drawing comes from the engine (tables, links, levels); this only keeps the zoom. */
class DiagramState {
  readonly diagram = signal<Diagram | null>(null);
  readonly error = signal<string | null>(null);
  readonly scale = signal(1);
  readonly picked = signal<string | null>(null);
}

/**
 * Every table of a database and how they link (foreign keys). A table sits above the tables it points at, as in the
 * service graph. Click a table to highlight its links; double-click to open it.
 */
@Component({
  selector: 'dd-diagram-view',
  imports: [Icon],
  template: `
    @let st = s();
    <div class="bar">
      <span class="mono where">{{ spec().service }}{{ spec().schema ? ' · ' + spec().schema : '' }}</span>
      @if (st.diagram(); as d) {
        <span class="dim small">{{ d.tables.length }} tables · {{ d.links.length }} links</span>
      }
      <span class="hint">Click a table to see its links · double-click to open it</span>
      <span class="grow"></span>
      <button class="ghost icon sm" title="Zoom out" (click)="zoom(1 / 1.2)"><dd-icon name="zoomOut" [size]="15" /></button>
      <span class="mono pct">{{ round(st.scale() * 100) }}%</span>
      <button class="ghost icon sm" title="Zoom in" (click)="zoom(1.2)"><dd-icon name="zoomIn" [size]="15" /></button>
      <button class="ghost icon sm" title="Read the tables again" (click)="load()"><dd-icon name="restart" [size]="14" /></button>
    </div>
    @if (st.error(); as e) {
      <div class="error">{{ e }}</div>
    }
    @if (layout(); as g) {
      <div class="canvas" (click)="st.picked.set(null)">
        <svg [attr.width]="g.width * st.scale()" [attr.height]="g.height * st.scale()" [attr.viewBox]="'0 0 ' + g.width + ' ' + g.height">
          <defs>
            <marker id="dd-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" class="tip" />
            </marker>
          </defs>
          @for (e of g.edges; track e.key) {
            <path [attr.d]="e.path" class="edge" [class.on]="isLit(e.from, e.to)" [class.dim]="st.picked() && !isLit(e.from, e.to)" marker-end="url(#dd-arrow)">
              <title>{{ e.label }}</title>
            </path>
          }
          @for (b of g.boxes; track b.id) {
            <g
              class="box"
              [class.outside]="b.outside"
              [class.on]="st.picked() === b.id"
              [class.dim]="st.picked() && !near(b.id)"
              [attr.transform]="'translate(' + b.x + ',' + b.y + ')'"
              (click)="$event.stopPropagation(); st.picked.set(b.id)"
              (dblclick)="open(b)"
            >
              <rect [attr.width]="W" [attr.height]="b.h" rx="6" class="card" />
              <rect [attr.width]="W" [attr.height]="HEAD" rx="6" class="head" />
              <text x="12" y="19" class="tname">{{ b.schema === 'public' || b.schema === 'main' ? b.name : b.schema + '.' + b.name }}</text>
              @if (b.rows >= 0) { <text [attr.x]="W - 10" y="19" class="rows" text-anchor="end">{{ count(b.rows) }}</text> }
              @for (c of b.columns; track c.name; let i = $index) {
                <text x="12" [attr.y]="HEAD + 14 + i * ROW" class="col" [class.pk]="c.primaryKey" [class.fk]="c.link">{{ c.primaryKey ? '◆' : '' }}{{ c.link ? '→' : '' }}{{ c.primaryKey || c.link ? ' ' : '' }}{{ c.name }}</text>
                <text [attr.x]="W - 10" [attr.y]="HEAD + 14 + i * ROW" class="type" text-anchor="end">{{ short(c.type) }}{{ c.nullable ? '' : ' !' }}</text>
              }
              @if (b.more) { <text x="12" [attr.y]="HEAD + 14 + b.columns.length * ROW" class="type">+ {{ b.more }} more columns</text> }
              @if (b.outside) { <text x="12" [attr.y]="HEAD + 16" class="type">in another schema</text> }
            </g>
          }
        </svg>
      </div>
      <div class="legend dim small"><span>◆ primary key</span><span>→ links to another table</span><span>! required (not null)</span></div>
    } @else if (!st.error()) {
      <div class="none dim">Reading the tables…</div>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 10px; padding: 8px 14px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
    .where { color: var(--text-1); }
    .hint { color: var(--text-3); font-size: var(--fs-xs); }
    .grow { flex: 1; }
    .pct { width: 44px; text-align: center; color: var(--text-3); font-size: var(--fs-xs); }
    .error { margin: 10px 14px; padding: 10px 12px; background: var(--err-bg); border-radius: var(--radius); font-size: var(--fs-sm); }
    .canvas { flex: 1; overflow: auto; min-height: 0; background: var(--bg-0); background-image: radial-gradient(var(--border) 1px, transparent 1px); background-size: 22px 22px; }
    .legend { display: flex; gap: 16px; padding: 6px 14px; border-top: 1px solid var(--border); }
    .none { padding: 24px; }
    svg { display: block; font-family: var(--font-mono); }
    .card { fill: var(--bg-1); stroke: var(--border-strong); }
    .head { fill: var(--bg-3); }
    .box { cursor: pointer; transition: opacity 0.15s; }
    .box.on .card { stroke: var(--accent); stroke-width: 2; }
    .box.dim, .edge.dim { opacity: 0.25; }
    .box.outside .card { stroke-dasharray: 4 3; fill: transparent; }
    .tname { fill: var(--text-1); font-size: 12.5px; font-weight: 600; }
    .rows { fill: var(--text-3); font-size: 11px; }
    .col { fill: var(--text-2); font-size: 11.5px; }
    .col.pk { fill: var(--warn); }
    .col.fk { fill: var(--accent); }
    .type { fill: var(--text-3); font-size: 10.5px; }
    .edge { fill: none; stroke: var(--text-3); stroke-width: 1.4; }
    .edge.on { stroke: var(--accent); stroke-width: 2; }
    .tip { fill: var(--text-3); }
  `,
})
export class DiagramViewComponent {
  readonly engine = inject(Engine);
  readonly wb = inject(Workbench);
  readonly doc = input.required<Doc>();
  readonly show = output<Doc>();
  readonly W = W;
  readonly HEAD = HEAD;
  readonly ROW = ROW;

  readonly s = computed(() => {
    const d = this.doc();
    if (!(d.state instanceof DiagramState)) d.state = new DiagramState();
    return d.state as DiagramState;
  });
  readonly spec = computed(() => this.doc().spec as { service: string; schema: string });

  constructor() {
    effect(() => {
      const st = this.s();
      if (!st.diagram()) untracked(() => this.load());
    });
  }

  async load(): Promise<void> {
    const st = this.s();
    const r = await this.engine.diagram(this.spec().service, this.spec().schema);
    if (r.ok) {
      st.diagram.set(r.value);
      st.error.set(null);
    } else {
      st.error.set(r.error);
    }
  }

  /** Drawing only: the engine already put each table in a level; here they get x/y and the arrows get curves. */
  readonly layout = computed(() => {
    const d = this.s().diagram();
    if (!d) return null;
    const byId = new Map(d.tables.map((t) => [t.id, t]));
    const levels = d.levels.map((l) => [...l]);
    if (d.outside.length) levels.push([...d.outside]);
    const height = (id: string) => {
      const t = byId.get(id);
      if (!t) return HEAD + 26;
      return HEAD + Math.min(t.columns.length, MAX_COLS) * ROW + (t.columns.length > MAX_COLS ? ROW : 0) + 8;
    };
    const widest = Math.max(...levels.map((l) => l.length), 1);
    const width = widest * (W + GAP_X) - GAP_X + PAD * 2;
    const boxes: Box[] = [];
    const pos = new Map<string, Box>();
    let y = PAD;
    for (const level of levels) {
      const offset = (width - (level.length * (W + GAP_X) - GAP_X)) / 2;
      let tallest = 0;
      level.forEach((id, i) => {
        const t = byId.get(id);
        const [schema, ...rest] = id.split('.');
        const b: Box = {
          id,
          name: t?.name ?? rest.join('.'),
          schema: t?.schema ?? schema,
          rows: t?.rows ?? -1,
          x: offset + i * (W + GAP_X),
          y,
          h: height(id),
          columns: (t?.columns ?? []).slice(0, MAX_COLS),
          more: Math.max(0, (t?.columns.length ?? 0) - MAX_COLS),
          outside: !t,
        };
        boxes.push(b);
        pos.set(id, b);
        tallest = Math.max(tallest, b.h);
      });
      y += tallest + GAP_Y;
    }
    // Several arrows leaving or entering one box get their own spot along its edge, ordered left to right.
    const outs = new Map<string, string[]>();
    const ins = new Map<string, string[]>();
    d.links.filter((l) => l.from !== l.to).forEach((l, i) => {
      outs.set(l.from, [...(outs.get(l.from) ?? []), `${l.to}#${i}`]);
      ins.set(l.to, [...(ins.get(l.to) ?? []), `${l.from}#${i}`]);
    });
    const xOf = (key: string) => pos.get(key.split('#')[0])?.x ?? 0;
    outs.forEach((l) => l.sort((a, b) => xOf(a) - xOf(b)));
    ins.forEach((l) => l.sort((a, b) => xOf(a) - xOf(b)));
    const spot = (index: number, count: number) => (count === 1 ? W / 2 : W * 0.25 + (W * 0.5 * index) / (count - 1));
    let n = -1;
    const edges = d.links.map((l, i) => {
      const a = pos.get(l.from)!;
      const b = pos.get(l.to)!;
      const label = `${l.from}(${l.columns.join(', ')}) → ${l.to}(${l.toColumns.join(', ')})`;
      if (l.from === l.to) {
        // A table pointing at itself (manager_id → id): a small loop on its right side.
        const x = a.x + W;
        return { key: `${i}`, from: l.from, to: l.to, label, path: `M${x},${a.y + 10} C${x + 40},${a.y + 10} ${x + 40},${a.y + 40} ${x},${a.y + 40}` };
      }
      n++;
      const down = b.y > a.y;
      const o = outs.get(l.from)!;
      const into = ins.get(l.to)!;
      const x1 = a.x + spot(o.indexOf(`${l.to}#${n}`), o.length);
      const y1 = down ? a.y + a.h : a.y;
      const x2 = b.x + spot(into.indexOf(`${l.from}#${n}`), into.length);
      const y2 = down ? b.y - 2 : b.y + b.h + 2;
      const bend = Math.max(30, Math.abs(y2 - y1) * 0.45) * (down ? 1 : -1);
      return { key: `${i}`, from: l.from, to: l.to, label, path: `M${x1},${y1} C${x1},${y1 + bend} ${x2},${y2 - bend} ${x2},${y2}` };
    });
    return { boxes, edges, width, height: y - GAP_Y + PAD };
  });

  isLit(from: string, to: string): boolean {
    const p = this.s().picked();
    return !!p && (from === p || to === p);
  }

  /** The picked table and the ones it links with stay bright. */
  near(id: string): boolean {
    const p = this.s().picked();
    if (!p || p === id) return true;
    return (this.s().diagram()?.links ?? []).some((l) => (l.from === p && l.to === id) || (l.to === p && l.from === id));
  }

  open(b: Box): void {
    if (b.outside) return;
    this.show.emit(this.wb.open({ kind: 'table', service: this.spec().service, schema: b.schema, table: b.name }));
  }

  zoom(by: number): void {
    this.s().scale.update((x) => Math.min(2, Math.max(0.3, x * by)));
  }

  /** "timestamp with time zone" → "timestamptz": the box has little room. */
  short(type: string): string {
    return type
      .replace('timestamp with time zone', 'timestamptz')
      .replace('timestamp without time zone', 'timestamp')
      .replace('character varying', 'varchar')
      .replace('double precision', 'float8');
  }

  count(n: number): string {
    return n >= 1000 ? `${Math.round(n / 100) / 10}k rows` : `${n} rows`;
  }

  round = Math.round;
}
