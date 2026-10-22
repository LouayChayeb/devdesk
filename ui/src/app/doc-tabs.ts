import { Component, inject, output } from '@angular/core';
import { Doc, Workbench } from './workbench';
import { Icon } from './icon';
import { openWindow } from './files';

const ICONS: Record<Doc['spec']['kind'], string> = { table: 'table', sql: 'sql', redis: 'key', request: 'send', activity: 'pulse', backups: 'archive', 'redis-tools': 'radio', run: 'play', mail: 'mail', diagram: 'graph', stats: 'pulse', slow: 'timeline', bucket: 'folder', queues: 'send', recorder: 'radio', stress: 'pulse', index: 'search', design: 'edit', mock: 'box', mongo: 'database' };

/** The strip of open Workbench tabs (tables, SQL editors, Redis key patterns, requests), like an editor's. */
@Component({
  selector: 'dd-doc-tabs',
  imports: [Icon],
  template: `
    @for (d of wb.docs(); track d.id) {
      <div
        class="doc"
        role="tab"
        [class.on]="d.id === wb.activeId()"
        [class.right]="d.id === wb.splitId()"
        [attr.aria-selected]="d.id === wb.activeId()"
        [title]="d.title"
        (click)="show.emit(d)"
        (auxclick)="$event.button === 1 && wb.close(d.id)"
      >
        <dd-icon [name]="icon(d)" [size]="13" />
        <span class="t">{{ d.title }}</span>
        @if (d.id === wb.splitId()) {
          <span class="side" title="Shown on the right half">R</span>
        }
        @if (d.dirty()) {
          <span class="dirty" title="Unsaved changes"></span>
        }
        <button class="ghost icon x" title="Pop out into its own window" (click)="$event.stopPropagation(); popOut(d)"><dd-icon name="external" [size]="11" /></button>
        <button class="ghost icon x" title="Close (Ctrl+W)" (click)="$event.stopPropagation(); wb.close(d.id)">
          <dd-icon name="close" [size]="11" />
        </button>
      </div>
    }
    <span class="rest"></span>
    @if (wb.docs().length > 1 || wb.splitId()) {
      <button class="ghost icon splitbtn" [class.on]="!!wb.splitId()" (click)="toggleSplit()" [title]="wb.splitId() ? hints.unsplit : hints.split">
        <dd-icon name="split" [size]="14" />
      </button>
    }
  `,
  styles: `
    :host { display: flex; height: 34px; flex: none; background: var(--bg-1); border-bottom: 1px solid var(--border); overflow-x: auto; overflow-y: hidden; scrollbar-width: none; }
    .doc { position: relative; display: flex; align-items: center; gap: 6px; padding: 0 4px 0 12px; max-width: 220px; border-right: 1px solid var(--border); color: var(--text-3); cursor: pointer; font-size: var(--fs-sm); flex: none; user-select: none; }
    .doc:hover { color: var(--text-1); background: var(--bg-2); }
    .doc.on { color: var(--text-1); background: var(--bg-0); }
    .doc.on::before { content: ''; position: absolute; left: 0; right: 0; top: 0; height: 2px; background: var(--accent); }
    .doc.on dd-icon { color: var(--accent); }
    .t { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: var(--font-mono); }
    .dirty { width: 7px; height: 7px; border-radius: 50%; background: var(--warn); flex: none; }
    .x { width: 20px; height: 20px; opacity: 0; }
    .doc:hover .x, .doc.on .x { opacity: 1; }
    .rest { flex: 1; }
    .side { font: 600 9px var(--font-mono); color: var(--accent); border: 1px solid var(--accent); border-radius: 3px; padding: 0 3px; line-height: 12px; }
    .splitbtn { flex: none; width: 34px; height: 33px; border-radius: 0; border-left: 1px solid var(--border); position: sticky; right: 0; background: var(--bg-1); }
    .splitbtn.on { color: var(--accent); }
  `,
})
export class DocTabs {
  readonly wb = inject(Workbench);
  /** The app shows the tab's page, then the tab. */
  readonly show = output<Doc>();
  icon = (d: Doc) => ICONS[d.spec.kind];

  /** "Pop out": the tab moves to its own window (its unsaved draft goes with it) and closes here. */
  async popOut(d: Doc): Promise<void> {
    const carried = typeof (d.state as { snapshot?: unknown } | undefined)?.snapshot === 'function';
    if (d.dirty() && !carried && !confirm(`"${d.title}" has unsaved changes that stay in this window's tab. Pop it out anyway (without them)?`)) return;
    const key = this.wb.handOver(d);
    await openWindow(key, d.title);
    if (this.wb.splitId() === d.id) this.wb.splitId.set(null);
    d.dirty.set(false);
    this.wb.close(d.id);
  }

  /** After a split the left half shows the tab used before: go to its page (it may be another Workbench page). */
  toggleSplit(): void {
    const a = this.wb.toggleSplit() ? this.wb.active() : null;
    if (a) this.show.emit(a);
  }
  readonly hints = {
    split: 'Split: this tab on the right, the one used before it on the left (Ctrl+\\)',
    unsplit: 'Back to one pane (Ctrl+\\)',
  };
}
