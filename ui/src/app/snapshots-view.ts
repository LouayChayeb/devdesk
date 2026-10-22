import { Component, OnInit, inject, output, signal } from '@angular/core';
import { Engine, SnapshotView } from './engine';
import { Icon } from './icon';
import { Autofocus } from './autofocus';
import { bytes } from './format';

/**
 * Snapshots ("save game"): every database, Redis and env file of the project saved under a name, and put back in
 * seconds. The engine saves and restores; this window names, lists and asks before replacing anything.
 */
@Component({
  selector: 'dd-snapshots',
  imports: [Icon, Autofocus],
  template: `
    <div class="scrim" (click)="!busy() && close.emit()">
      <div class="panel" role="dialog" aria-label="Snapshots" (click)="$event.stopPropagation()">
        <header>
          <h3>Snapshots</h3>
          <span class="dim small">All databases, Redis and .env files together. Restore puts every one back exactly as it was.</span>
          <span class="grow"></span>
          <button class="ghost icon sm" title="Close (Esc)" [disabled]="busy()" (click)="close.emit()"><dd-icon name="close" [size]="14" /></button>
        </header>

        <div class="save">
          <input class="mono grow" placeholder="Name, e.g. before-migration" [value]="label()" ddAutofocus [disabled]="busy()"
                 (input)="label.set($any($event.target).value)" (keydown.enter)="save()" (keydown.escape)="close.emit()" />
          <button class="primary" [disabled]="busy()" (click)="save()"><dd-icon name="save" [size]="13" /> Save snapshot</button>
        </div>
        @if (busy(); as b) { <p class="dim small">{{ b }}</p> }
        @if (error(); as e) { <p class="problem small">{{ e }}</p> }
        @if (result(); as r) {
          <div class="result">
            <div class="small"><b>{{ r.title }}</b></div>
            @for (line of r.lines; track $index) {
              <div class="mono small" [class.err]="line.bad" [class.dim]="!line.bad">{{ line.text }}</div>
            }
          </div>
        }

        <div class="list">
          @for (s of snapshots(); track s.id) {
            <div class="snap" [class.open]="confirming() === s.id">
              <div class="row">
                <dd-icon name="archive" [size]="14" />
                <span class="name">{{ s.label }}</span>
                <span class="dim small mono">{{ when(s.created) }} · {{ bytes(s.bytes) }}</span>
                @if (!s.complete) { <span class="warn small" [title]="missing(s)">partial</span> }
                <span class="grow"></span>
                <button class="sm" [disabled]="!!busy()" (click)="confirming.set(confirming() === s.id ? null : s.id)"><dd-icon name="undo" [size]="12" /> Restore</button>
                <button class="ghost icon sm" title="Delete this snapshot" [disabled]="!!busy()" (click)="remove(s)"><dd-icon name="trash" [size]="13" /></button>
              </div>
              <div class="parts small">
                @for (i of s.items; track i.kind + i.name) {
                  <span class="part" [class.missing]="!i.file" [title]="i.problem ?? ''">
                    {{ i.kind === 'settings' ? i.name : i.name + ' (' + (i.kind === 'redis' ? 'Redis' : 'database') + ')' }}{{ i.file ? '' : ': not saved' }}
                  </span>
                }
              </div>
              @if (confirming() === s.id) {
                <div class="confirm">
                  <p class="small">
                    This replaces the current data of
                    <b>{{ saved(s) }}</b>
                    with this snapshot. Tables, keys and collections made since then are removed.
                  </p>
                  <label class="small"><input type="checkbox" [checked]="saveFirst()" (change)="saveFirst.set(!saveFirst())" /> Save the current state first, so I can come back to it</label>
                  <div class="actions">
                    <button class="ghost sm" (click)="confirming.set(null)">Cancel</button>
                    <button class="danger sm" (click)="restore(s)"><dd-icon name="undo" [size]="12" /> Restore "{{ s.label }}"</button>
                  </div>
                </div>
              }
            </div>
          } @empty {
            <p class="dim small">No snapshots yet. Save one before trying something risky: a migration, a big import, a test that deletes data.</p>
          }
        </div>
      </div>
    </div>
  `,
  styles: `
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); display: flex; align-items: center; justify-content: center; z-index: 30; }
    .panel { width: min(820px, 94vw); max-height: 86vh; display: flex; flex-direction: column; gap: 10px; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); padding: 16px 20px; }
    header { display: flex; align-items: baseline; gap: 10px; }
    h3 { margin: 0; font: 700 var(--fs-xl) var(--font-display); }
    .grow { flex: 1; }
    .save { display: flex; gap: 6px; }
    input.mono { height: 30px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 8px; }
    .list { overflow: auto; display: flex; flex-direction: column; gap: 6px; }
    .snap { border: 1px solid var(--border); border-radius: var(--radius); padding: 8px 10px; background: var(--bg-0); }
    .snap.open { border-color: var(--border-hover); }
    .row { display: flex; align-items: center; gap: 8px; }
    .name { font-weight: 600; }
    .parts { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
    .part { font-family: var(--font-mono); color: var(--text-2); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 0 6px; }
    .part.missing { color: var(--warn); border-color: var(--warn); border-style: dashed; }
    .warn { color: var(--warn); }
    .err { color: var(--err); }
    .confirm { margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--border); display: flex; flex-direction: column; gap: 6px; }
    .confirm p { margin: 0; }
    .confirm label { display: flex; gap: 6px; align-items: center; color: var(--text-2); }
    .actions { display: flex; justify-content: flex-end; gap: 6px; }
    .result { background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 8px 10px; }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); margin: 0; }
  `,
})
export class SnapshotsView implements OnInit {
  readonly engine = inject(Engine);
  readonly close = output<void>();
  readonly snapshots = signal<SnapshotView[]>([]);
  readonly label = signal('');
  readonly busy = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly confirming = signal<string | null>(null);
  readonly saveFirst = signal(true);
  readonly result = signal<{ title: string; lines: { text: string; bad: boolean }[] } | null>(null);
  readonly bytes = bytes;

  async ngOnInit(): Promise<void> {
    await this.load();
  }

  private async load(): Promise<void> {
    const r = await this.engine.snapshots();
    if (r.ok) this.snapshots.set(r.value);
    else this.error.set(r.error);
  }

  async save(): Promise<void> {
    if (this.busy()) return;
    this.error.set(null);
    this.result.set(null);
    this.busy.set('Saving every database, Redis and env file…');
    const r = await this.engine.saveSnapshot(this.label().trim() || 'snapshot');
    this.busy.set(null);
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    this.label.set('');
    const s = r.value;
    this.result.set({
      title: s.complete ? `Saved "${s.label}" (${bytes(s.bytes)}).` : `Saved "${s.label}", but not everything:`,
      lines: s.items.filter((i) => i.problem).map((i) => ({ text: `${i.name}: ${i.problem}`, bad: !i.file })),
    });
    await this.load();
  }

  async restore(s: SnapshotView): Promise<void> {
    this.confirming.set(null);
    this.error.set(null);
    this.result.set(null);
    this.busy.set(`Restoring "${s.label}"…`);
    const r = await this.engine.restoreSnapshot(s.id, this.saveFirst());
    this.busy.set(null);
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    const bad = r.value.results.filter((x) => x.result.startsWith('failed') || x.result.startsWith('skipped'));
    this.result.set({
      title: `${bad.length ? 'Restored with problems' : 'Restored'} "${s.label}" in ${(r.value.ms / 1000).toFixed(1)} s${r.value.savedFirst ? `. The state before is saved as "${r.value.savedFirst.label}".` : '.'}`,
      lines: r.value.results.map((x) => ({ text: `${x.name}: ${x.result}`, bad: x.result.startsWith('failed') || x.result.startsWith('skipped') })),
    });
    await this.load();
  }

  async remove(s: SnapshotView): Promise<void> {
    if (!confirm(`Delete the snapshot "${s.label}"? Its files are removed for good.`)) return;
    const r = await this.engine.deleteSnapshot(s.id);
    if (r.ok) this.snapshots.set(r.value);
    else this.error.set(r.error);
  }

  saved(s: SnapshotView): string {
    return s.items.filter((i) => i.file).map((i) => i.name).join(', ') || 'nothing';
  }

  missing(s: SnapshotView): string {
    return s.items.filter((i) => !i.file).map((i) => `${i.name}: ${i.problem}`).join('\n');
  }

  when(iso: string): string {
    const d = new Date(iso);
    return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }) + ' ' + d.toLocaleTimeString('en-GB', { hour12: false });
  }
}
