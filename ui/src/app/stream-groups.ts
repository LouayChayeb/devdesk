import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Engine, StreamInfoView } from './engine';
import { Icon } from './icon';

/** Under a stream's entries: add an entry, its consumer groups, who holds unacknowledged messages, and hints. */
@Component({
  selector: 'dd-stream-groups',
  imports: [Icon],
  template: `
    <div class="add">
      <input #f class="mono grow-in" placeholder="field=value, field2=value2" (keydown.enter)="add(f.value); f.value = ''" />
      <button class="sm" (click)="add(f.value); f.value = ''"><dd-icon name="plus" [size]="11" /> Add entry</button>
    </div>
    @if (error(); as e) { <div class="err small">{{ e }}</div> }
    @if (info(); as i) {
      @for (h of i.hints; track $index) {
        <div class="hint"><dd-icon name="diagnosis" [size]="14" /><span>{{ h }}</span></div>
      }
      <div class="label">Consumer groups <span class="dim">({{ i.groups.length }})</span></div>
      @for (g of i.groups; track g.name) {
        <div class="group">
          <div class="ghead mono">
            <b>{{ g.name }}</b>
            <span class="dim">read up to {{ g.lastDelivered }}</span>
            <span [class.warn]="g.lag > 0">{{ g.lag < 0 ? '? behind' : g.lag + ' behind' }}</span>
            <span [class.warn]="g.pending > 0">{{ g.pending }} pending</span>
            <span class="grow"></span>
            <button class="ghost icon sm" title="Delete this group (its pending list goes with it)" (click)="dropGroup(g.name)"><dd-icon name="trash" [size]="12" /></button>
          </div>
          @for (c of g.consumers; track c.name) {
            <div class="consumer mono"><span>{{ c.name }}</span><span [class.warn]="c.pending > 0">{{ c.pending }} pending</span><span class="dim">quiet {{ idle(c.idleMs) }}</span></div>
          } @empty {
            <div class="dim small consumer">no consumers yet</div>
          }
        </div>
      }
      <div class="add">
        <input #g class="mono grow-in" placeholder="new group name" />
        <label class="dim small"><input #start type="checkbox" /> read from the first entry</label>
        <button class="sm" (click)="newGroup(g.value, start.checked); g.value = ''"><dd-icon name="plus" [size]="11" /> Group</button>
      </div>
    }
  `,
  styles: `
    :host { display: block; margin-top: 12px; padding: 0 14px 12px; }
    .add { display: flex; gap: 6px; align-items: center; margin: 8px 0; }
    .grow-in { flex: 1; }
    .label { font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin: 12px 0 6px; }
    .hint { display: flex; gap: 8px; padding: 8px 10px; margin: 6px 0; border: 1px solid rgba(230, 180, 80, 0.35); border-radius: var(--radius); font-size: var(--fs-sm); }
    .hint dd-icon { color: var(--warn); flex: none; margin-top: 2px; }
    .group { border: 1px solid var(--border); border-radius: var(--radius); padding: 6px 10px; margin-bottom: 6px; }
    .ghead { display: flex; gap: 12px; align-items: center; font-size: var(--fs-sm); }
    .grow { flex: 1; }
    .consumer { display: flex; gap: 16px; padding: 2px 0 2px 16px; font-size: var(--fs-sm); color: var(--text-2); }
    .warn { color: var(--warn); }
    .err { color: var(--err); }
  `,
})
export class StreamGroups {
  readonly engine = inject(Engine);
  readonly service = input.required<string>();
  readonly streamKey = input.required<string>();
  /** The stream changed: the entries above reload. */
  readonly changed = output<void>();
  readonly info = signal<StreamInfoView | null>(null);
  readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      void this.streamKey();
      untracked(() => this.load());
    });
  }

  async load(): Promise<void> {
    const r = await this.engine.stream(this.service(), this.streamKey());
    this.error.set(r.ok ? null : r.error);
    if (r.ok) this.info.set(r.value);
  }

  async add(text: string): Promise<void> {
    const fields = text
      .split(',')
      .map((p) => p.trim())
      .filter(Boolean)
      .map((p) => {
        const eq = p.indexOf('=');
        return eq < 0 ? { name: p, value: '' } : { name: p.slice(0, eq).trim(), value: p.slice(eq + 1).trim() };
      });
    if (!fields.length) return;
    await this.edit({ op: 'stream-add', fields });
  }

  newGroup(name: string, fromStart: boolean): Promise<void> {
    return name.trim() ? this.edit({ op: 'group-create', group: name.trim(), fromStart }) : Promise.resolve();
  }

  dropGroup(name: string): Promise<void> {
    return confirm(`Delete the group ${name}? Its consumers lose their place and pending list.`) ? this.edit({ op: 'group-delete', group: name }) : Promise.resolve();
  }

  private async edit(edit: Parameters<Engine['editKey']>[2]): Promise<void> {
    const r = await this.engine.editKey(this.service(), this.streamKey(), edit);
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    await this.load();
    this.changed.emit();
  }

  idle(ms: number): string {
    const s = Math.round(ms / 1000);
    return s < 60 ? `${s} s` : s < 3600 ? `${Math.round(s / 60)} min` : `${Math.floor(s / 3600)} h ${Math.round((s % 3600) / 60)} min`;
  }
}
