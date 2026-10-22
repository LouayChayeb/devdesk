import { Component, computed, input } from '@angular/core';
import { PlanNodeView, PlanView as Plan } from './engine';
import { Icon } from './icon';

/** One step of a query plan and the steps under it (the database reads from the deepest steps upward). */
@Component({
  selector: 'dd-plan-node',
  template: `
    @let n = node();
    <div class="node" [class.slow]="share() > 0.4" [class.scan]="isScan()">
      <div class="head">
        <span class="type">{{ n.type }}</span>
        @if (n.relation) { <span class="rel mono">{{ n.relation }}</span> }
        <span class="grow"></span>
        @if (n.actualRows !== null) {
          <span class="num mono" [class.off]="estimateOff()" [title]="'expected ' + n.planRows.toLocaleString() + ' rows'">{{ n.actualRows.toLocaleString() }} rows</span>
        } @else if (n.planRows) {
          <span class="num mono dim" title="the planner's estimate">~{{ n.planRows.toLocaleString() }} rows</span>
        }
        @if (n.removed) { <span class="num mono warn" title="rows read, then thrown away by the filter">−{{ n.removed.toLocaleString() }} filtered</span> }
        @if (n.ms !== null) { <span class="num mono">{{ n.ms < 1 ? n.ms.toFixed(2) : n.ms.toFixed(1) }} ms</span> }
        @if (n.spill) { <span class="tag err">sorted on disk</span> }
      </div>
      @if (n.ms !== null && total()) {
        <div class="bar"><span [style.width.%]="share() * 100"></span></div>
      }
      @for (d of n.detail; track $index) { <div class="detail mono">{{ d }}</div> }
    </div>
    @if (n.children.length) {
      <div class="kids">
        @for (c of n.children; track $index) { <dd-plan-node [node]="c" [total]="total()" /> }
      </div>
    }
  `,
  styles: `
    :host { display: block; }
    .node { border: 1px solid var(--border); border-radius: var(--radius); padding: 6px 10px; background: var(--bg-1); margin: 4px 0; }
    .node.slow { border-color: var(--warn); }
    .head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .type { font-weight: 600; }
    .scan .type { color: var(--warn); }
    .rel { color: var(--accent); }
    .grow { flex: 1; }
    .num { font-size: var(--fs-xs); color: var(--text-2); }
    .num.off { color: var(--warn); }
    .warn { color: var(--warn); }
    .dim { color: var(--text-3); }
    .tag { font-size: 10px; border-radius: 4px; padding: 0 5px; }
    .tag.err { color: var(--err); background: var(--err-bg); }
    .bar { height: 3px; background: var(--bg-2); border-radius: 2px; margin: 5px 0 2px; overflow: hidden; }
    .bar span { display: block; height: 100%; background: var(--accent); }
    .slow .bar span { background: var(--warn); }
    .detail { font-size: var(--fs-xs); color: var(--text-3); white-space: pre-wrap; word-break: break-word; }
    .kids { margin-left: 18px; padding-left: 10px; border-left: 1px dashed var(--border-strong); }
  `,
})
export class PlanNode {
  readonly node = input.required<PlanNodeView>();
  /** The whole query's time, to show each step's share. */
  readonly total = input<number | null>(null);
  readonly share = computed(() => {
    const t = this.total();
    const ms = this.node().ms;
    return t && ms !== null ? Math.min(1, ms / t) : 0;
  });
  readonly isScan = computed(() => ['Seq Scan', 'Table scan'].includes(this.node().type));
  /** The planner guessed 10× wrong: its statistics are probably stale. */
  readonly estimateOff = computed(() => {
    const n = this.node();
    if (n.actualRows === null || n.planRows === 0) return false;
    const r = Math.max(n.actualRows, 1) / Math.max(n.planRows, 1);
    return (r > 10 || r < 0.1) && Math.max(n.actualRows, n.planRows) > 1000;
  });
}

/** A whole query plan: DevDesk's hints in plain words first, then the tree of steps. */
@Component({
  selector: 'dd-plan',
  imports: [PlanNode, Icon],
  template: `
    @let p = plan();
    <div class="sum">
      @if (p.executionMs !== null) {
        <span>ran in</span><b class="mono">{{ p.executionMs.toFixed(1) }} ms</b>
        @if (p.planningMs !== null) { <span class="dim">· planning</span><span class="dim mono">{{ p.planningMs.toFixed(1) }} ms</span> }
      } @else {
        <span class="dim">Estimated plan: the query was not run. Use Analyze to run it and see real numbers.</span>
      }
      <span class="grow"></span>
      <button class="ghost sm" (click)="copy()" title="Copy the database's own plan output"><dd-icon name="copy" [size]="12" /> Copy raw</button>
    </div>
    @for (h of p.hints; track $index) {
      <div class="hint"><dd-icon name="diagnosis" [size]="14" /> <span>{{ h }}</span></div>
    }
    <dd-plan-node [node]="p.root" [total]="p.executionMs ?? p.root.ms" />
  `,
  styles: `
    :host { display: block; padding: 10px 14px; overflow: auto; }
    .sum { display: flex; align-items: center; gap: 6px; color: var(--text-2); font-size: var(--fs-sm); margin-bottom: 8px; }
    .grow { flex: 1; }
    .dim { color: var(--text-3); }
    .hint { display: flex; gap: 8px; align-items: flex-start; padding: 8px 10px; margin-bottom: 8px; border-radius: var(--radius); background: var(--warn-bg); border: 1px solid var(--warn); font-size: var(--fs-sm); }
    .hint dd-icon { color: var(--warn); flex: none; margin-top: 2px; }
  `,
})
export class PlanView {
  readonly plan = input.required<Plan>();
  copy(): void {
    navigator.clipboard?.writeText(this.plan().raw);
  }
}
