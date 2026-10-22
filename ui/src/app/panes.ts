import { Component, inject, input, output } from '@angular/core';
import { Doc, Workbench } from './workbench';
import { DocView } from './doc-view';
import { Icon } from './icon';

/**
 * The main area of a Workbench page: the page's tab on the left and, in split view, the split tab on the right.
 * What to show when the page has no tab yet comes as content.
 */
@Component({
  selector: 'dd-panes',
  imports: [DocView, Icon],
  template: `
    <div class="pane" (pointerdown)="focus(left())">
      @if (left(); as doc) {
        <dd-doc-view [doc]="doc" (toast)="toast.emit($event)" (show)="show.emit($event)" (variables)="variables.emit()" (logs)="logs.emit($event)" />
      } @else {
        <ng-content />
      }
    </div>
    @if (wb.split(); as doc) {
      <div class="pane right" [class.on]="wb.activeId() === doc.id" (pointerdown)="focus(doc)">
        <div class="head">
          <dd-icon name="split" [size]="12" />
          <span class="t mono">{{ doc.title }}</span>
          <button class="ghost icon sm" title="Back to one pane (the tab stays open)" (click)="wb.splitId.set(null)"><dd-icon name="close" [size]="12" /></button>
        </div>
        <dd-doc-view [doc]="doc" (toast)="toast.emit($event)" (show)="show.emit($event)" (variables)="variables.emit()" (logs)="logs.emit($event)" />
      </div>
    }
  `,
  styles: `
    :host { display: flex; min-height: 0; min-width: 0; }
    .pane { flex: 1 1 0; display: flex; flex-direction: column; min-width: 0; min-height: 0; }
    .pane > dd-doc-view { flex: 1; min-height: 0; }
    .right { border-left: 1px solid var(--border-strong); }
    .head { display: flex; align-items: center; gap: 6px; height: 26px; flex: none; padding: 0 4px 0 10px; background: var(--bg-1); border-bottom: 1px solid var(--border); color: var(--text-3); font-size: var(--fs-xs); }
    .right.on .head { color: var(--text-1); box-shadow: inset 0 2px 0 var(--accent); }
    .t { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  `,
})
export class Panes {
  readonly wb = inject(Workbench);
  readonly left = input<Doc | null>(null);
  readonly toast = output<string>();
  readonly show = output<Doc>();
  readonly variables = output<void>();
  readonly logs = output<string>();

  /** Clicking into a half makes its tab the active one (Ctrl+W, Ctrl+S and the path at the top follow it). */
  focus(doc: Doc | null): void {
    if (doc && this.wb.activeId() !== doc.id) this.wb.activeId.set(doc.id);
  }
}
