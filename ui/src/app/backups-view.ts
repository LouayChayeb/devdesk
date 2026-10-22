import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { BackupView, Engine } from './engine';
import { Catalog } from './catalog';
import { Doc } from './workbench';
import { Icon } from './icon';
import { bytes } from './format';

/**
 * Whole-database snapshots of one database: save one before trying something risky (a migration, a big edit),
 * restore it to go back. The engine runs the database's own dump tool and keeps the files.
 */
@Component({
  selector: 'dd-backups-view',
  imports: [Icon],
  template: `
    <div class="bar">
      <span class="title"><dd-icon name="archive" [size]="14" /> Backups of <b class="mono">{{ service() }}</b></span>
      <span class="grow"></span>
      <input #label class="label-input" placeholder="label, e.g. before migration" (keydown.enter)="save(label.value); label.value = ''" />
      <button class="sm primary" [class.busy]="busy()" (click)="save(label.value); label.value = ''"><dd-icon name="save" [size]="12" /> Save now</button>
    </div>
    <p class="about dim">
      {{ kindLabel() }} Files are kept in <span class="mono">{{ folder() }}</span>.
    </p>
    @if (error(); as e) { <div class="error mono">{{ e }}</div> }
    <div class="list">
      @for (b of list(); track b.name) {
        <div class="item" [class.confirm]="confirming() === b.name">
          <dd-icon name="archive" [size]="14" />
          <span class="name mono">{{ b.name }}</span>
          <span class="dim small">{{ when(b.at) }} · {{ bytes(b.bytes) }}</span>
          <span class="grow"></span>
          @if (confirming() === b.name) {
            <span class="warn small">Replace everything in {{ service() }} with this backup?</span>
            <button class="sm ghost" (click)="confirming.set(null)">Back</button>
            <button class="sm danger" [class.busy]="busy()" (click)="restore(b)">Restore</button>
          } @else {
            <button class="sm" (click)="confirming.set(b.name)" title="Put the database back as it was in this backup"><dd-icon name="undo" [size]="12" /> Restore</button>
            <button class="ghost icon sm" title="Delete this backup file" (click)="remove(b)"><dd-icon name="trash" [size]="12" /></button>
          }
        </div>
      } @empty {
        <div class="none">No backups yet. Save one before trying something you may want to undo.</div>
      }
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; height: 100%; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 10px; padding: 8px 14px; border-bottom: 1px solid var(--border); }
    .title { display: inline-flex; align-items: center; gap: 6px; }
    .title dd-icon { color: var(--accent); }
    .label-input { height: 26px; width: 240px; font-size: var(--fs-sm); }
    .grow { flex: 1; }
    .about { margin: 10px 14px 4px; font-size: var(--fs-sm); }
    .dim { color: var(--text-3); }
    .small { font-size: var(--fs-sm); }
    .warn { color: var(--warn); }
    .error { margin: 10px 14px; padding: 10px 12px; background: var(--err-bg); border: 1px solid rgba(240, 100, 90, 0.4); border-radius: var(--radius); white-space: pre-wrap; font-size: var(--fs-sm); }
    .list { padding: 6px 14px; overflow: auto; display: flex; flex-direction: column; gap: 6px; }
    .item { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-1); }
    .item.confirm { border-color: var(--err); background: var(--err-bg); }
    .item > dd-icon { color: var(--text-3); }
    .name { font-size: var(--fs-sm); }
    .none { color: var(--text-3); padding: 12px 0; }
  `,
})
export class BackupsView {
  readonly engine = inject(Engine);
  readonly catalog = inject(Catalog);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();
  readonly service = computed(() => (this.doc().spec as { service: string }).service);
  readonly list = signal<BackupView[]>([]);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly confirming = signal<string | null>(null);
  readonly bytes = bytes;
  readonly folder = computed(() => {
    const first = this.list()[0]?.path;
    return first ? first.slice(0, first.lastIndexOf('/')) : 'devdesk/backups/ in your state folder ($XDG_STATE_HOME, usually ~/.local/state)';
  });
  readonly kindLabel = computed(() => {
    switch (this.catalog.database(this.service())?.kind) {
      case 'postgres':
        return 'Made with pg_dump inside the container; restoring runs psql in one transaction (all or nothing).';
      case 'mysql':
        return 'Made with mysqldump (mariadb-dump) inside the container. Restoring replaces the tables in the backup.';
      case 'sqlite':
        return "A copy made with SQLite's backup API, safe while your app writes. Restoring copies it back over the file.";
      default:
        return '';
    }
  });

  constructor() {
    effect(() => {
      this.service();
      untracked(() => this.load());
    });
  }

  async load(): Promise<void> {
    const r = await this.engine.backups(this.service());
    this.error.set(r.ok ? null : r.error);
    if (r.ok) this.list.set(r.value);
  }

  async save(label: string): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    const r = await this.engine.saveBackup(this.service(), label.trim());
    this.busy.set(false);
    this.error.set(r.ok ? null : r.error);
    if (r.ok) {
      this.toast.emit(`Saved ${r.value.name} (${bytes(r.value.bytes)}, ${r.value.ms} ms)`);
      this.load();
    }
  }

  async restore(b: BackupView): Promise<void> {
    this.busy.set(true);
    const r = await this.engine.restoreBackup(this.service(), b.name);
    this.busy.set(false);
    this.confirming.set(null);
    this.error.set(r.ok ? null : r.error);
    if (r.ok) {
      this.toast.emit(`Restored ${b.name} (${r.value.ms} ms)`);
      this.catalog.loadTables(this.service());
    }
  }

  async remove(b: BackupView): Promise<void> {
    if (!confirm(`Delete the backup file ${b.name}?`)) return;
    const r = await this.engine.deleteBackup(this.service(), b.name);
    this.error.set(r.ok ? null : r.error);
    if (r.ok) this.list.set(r.value);
  }

  when(iso: string): string {
    return new Date(iso).toLocaleString();
  }
}
