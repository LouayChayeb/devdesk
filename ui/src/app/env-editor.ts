import { Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { Engine, EnvFileView, EnvOverview } from './engine';
import { Icon } from './icon';

/**
 * The .env editor: every env file of the open project, which service uses each variable, what's missing. Secret values
 * stay in the engine until "show" is clicked. The engine writes the files (keeping comments) and reloads the project.
 */
@Component({
  selector: 'dd-env-editor',
  imports: [Icon],
  template: `
    <div class="scrim" (click)="close.emit()">
      <div class="panel" role="dialog" aria-label="Environment" (click)="$event.stopPropagation()">
        <header>
          <h3>Environment</h3>
          <span class="dim small">The .env files of {{ engine.project()?.name }}. Changes are saved right away.</span>
          <span class="grow"></span>
          <button class="ghost icon sm" title="Close" (click)="close.emit()"><dd-icon name="close" [size]="14" /></button>
        </header>

        @if (view(); as v) {
          <div class="tabs">
            @for (f of v.files; track f.name) {
              <button class="tab" [class.on]="current()?.name === f.name" (click)="tab.set(f.name)">
                <span class="mono">{{ f.name }}</span>
                @if (f.everyService) { <span class="badge">every service</span> }
                @else if (f.example) { <span class="badge dim">example</span> }
                @else if (!f.exists) { <span class="badge dim">not created</span> }
              </button>
            } @empty {
              <span class="dim small pad">No .env files yet: add a variable below to create .env.</span>
            }
          </div>

          <div class="body">
            @if (v.restartNeeded) {
              <p class="note small">Saved. The project is running: restart the services that use it to pick up the new value.</p>
            }
            @for (w of v.warnings; track $index) { <p class="note small">{{ w }}</p> }

            @if (current(); as f) {
              <p class="dim small">
                @if (f.everyService) { devdesk.yaml lists this file in env_files: every service gets these variables. }
                @else if (f.example) { An example for teammates: it's meant for git, so put no real secrets here. }
                @else { devdesk.yaml doesn't list this file: only programs that read it themselves (dotenv) see it. }
              </p>
              <table>
                <thead><tr><th>name</th><th>value</th><th>used by</th><th></th></tr></thead>
                <tbody>
                  @for (row of f.variables; track row.key) {
                    <tr>
                      <td class="mono key">{{ row.key }}</td>
                      <td>
                        @if (editing() === f.name + '/' + row.key) {
                          <input class="mono" [value]="draftValue()" (input)="draftValue.set($any($event.target).value)" (keydown.enter)="save(f.name, row.key)" (keydown.escape)="editing.set(null)" autofocus />
                        } @else if (row.secret && revealed()[f.name + '/' + row.key] === undefined) {
                          <span class="mono dim">●●●●●●</span>
                          <button class="ghost sm" (click)="reveal(f.name, row.key)"><dd-icon name="eye" [size]="12" /> show</button>
                        } @else {
                          <span class="mono value">{{ row.secret ? revealed()[f.name + '/' + row.key] : row.value }}</span>
                        }
                      </td>
                      <td class="dim small">{{ row.usedBy.length ? row.usedBy.join(', ') : (f.everyService ? 'every service' : '–') }}</td>
                      <td class="actions">
                        @if (editing() === f.name + '/' + row.key) {
                          <button class="sm primary" (click)="save(f.name, row.key)">Save</button>
                        } @else {
                          <button class="ghost icon sm" title="Change" (click)="startEdit(f.name, row.key, row.secret ? '' : (row.value ?? ''))"><dd-icon name="edit" [size]="12" /></button>
                          <button class="ghost icon sm" title="Remove from {{ f.name }}" (click)="remove(f.name, row.key)"><dd-icon name="trash" [size]="12" /></button>
                        }
                      </td>
                    </tr>
                  } @empty {
                    <tr><td colspan="4" class="dim">{{ f.exists ? 'This file sets nothing yet.' : 'This file doesn\\'t exist yet: adding a variable creates it.' }}</td></tr>
                  }
                </tbody>
              </table>
            }

            <div class="add">
              <input class="mono" placeholder="NEW_NAME" [value]="newKey()" (input)="newKey.set($any($event.target).value)" />
              <input class="mono grow" placeholder="value" [value]="newValue()" (input)="newValue.set($any($event.target).value)" (keydown.enter)="add()" />
              <button class="sm" [disabled]="!newKey()" (click)="add()"><dd-icon name="plus" [size]="12" /> Add to {{ current()?.name ?? '.env' }}</button>
            </div>

            @if (v.missing.length) {
              <h4>Missing</h4>
              @for (m of v.missing; track m.key) {
                <div class="missing">
                  <div><span class="mono">{{ m.key }}</span><div class="dim small">{{ m.reason }}</div></div>
                  <button class="sm" (click)="newKey.set(m.key); newValue.set('')">Fill in</button>
                </div>
              }
            }
            @if (error(); as e) { <p class="problem small">{{ e }}</p> }
          </div>
        } @else {
          <div class="body"><p class="dim">Reading the env files…</p></div>
        }
      </div>
    </div>
  `,
  styles: `
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); display: flex; align-items: center; justify-content: center; z-index: 30; }
    .panel { width: min(900px, 94vw); max-height: 86vh; display: flex; flex-direction: column; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); }
    header { display: flex; align-items: baseline; gap: 12px; padding: 16px 20px 10px; }
    h3 { margin: 0; font: 700 var(--fs-xl) var(--font-display); }
    h4 { margin: 18px 0 8px; font-size: var(--fs-xs); color: var(--text-2); text-transform: uppercase; letter-spacing: 0.04em; }
    .grow { flex: 1; }
    .tabs { display: flex; gap: 4px; padding: 0 20px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
    .tab { background: transparent; border: none; border-bottom: 2px solid transparent; border-radius: 0; color: var(--text-2); gap: 6px; }
    .tab.on { color: var(--text-1); border-bottom-color: var(--accent); }
    .badge { font-size: var(--fs-xs); background: var(--accent-deep); color: var(--accent); border-radius: var(--radius-badge); padding: 0 5px; }
    .badge.dim { background: var(--bg-3); color: var(--text-3); }
    .pad { padding: 8px 0; }
    .body { flex: 1; overflow: auto; padding: 12px 20px 18px; }
    table { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
    th { text-align: left; color: var(--text-3); font-weight: 500; font-size: var(--fs-xs); padding: 4px 8px; border-bottom: 1px solid var(--border); }
    td { padding: 5px 8px; border-bottom: 1px solid var(--border); vertical-align: middle; }
    .key { white-space: nowrap; }
    .value { word-break: break-all; }
    .actions { white-space: nowrap; text-align: right; }
    input { height: 28px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 8px; min-width: 0; }
    td input { width: 100%; }
    .add { display: flex; gap: 6px; margin-top: 12px; }
    .add input:first-child { width: 200px; }
    .missing { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 6px 10px; border: 1px solid var(--border); border-radius: var(--radius); margin-bottom: 6px; }
    .note { background: var(--warn-bg); border-left: 2px solid var(--warn); padding: 6px 10px; border-radius: var(--radius-badge); }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); }
  `,
})
export class EnvEditor implements OnInit {
  readonly engine = inject(Engine);
  readonly close = output<void>();

  readonly view = signal<EnvOverview | null>(null);
  readonly tab = signal<string | null>(null);
  readonly editing = signal<string | null>(null);
  readonly draftValue = signal('');
  readonly revealed = signal<Record<string, string>>({});
  readonly newKey = signal('');
  readonly newValue = signal('');
  readonly error = signal<string | null>(null);

  /** The chosen tab, else the first file every service gets, else the first file. */
  readonly current = computed<EnvFileView | null>(() => {
    const files = this.view()?.files ?? [];
    return files.find((f) => f.name === this.tab()) ?? files.find((f) => f.everyService) ?? files[0] ?? null;
  });

  async ngOnInit(): Promise<void> {
    const r = await this.engine.envOverview();
    if (r.ok) this.view.set(r.value);
    else this.error.set(r.error);
  }

  startEdit(file: string, key: string, value: string): void {
    this.editing.set(file + '/' + key);
    this.draftValue.set(value);
  }

  async save(file: string, key: string): Promise<void> {
    await this.apply(this.engine.envSet(file, key, this.draftValue()));
    this.editing.set(null);
    this.revealed.update(({ [file + '/' + key]: _old, ...rest }) => rest);
  }

  async remove(file: string, key: string): Promise<void> {
    if (!confirm(`Remove ${key} from ${file}?`)) return;
    await this.apply(this.engine.envDelete(file, key));
  }

  async add(): Promise<void> {
    const key = this.newKey().trim();
    if (!key) return;
    await this.apply(this.engine.envSet(this.current()?.name ?? '.env', key, this.newValue()));
    this.newKey.set('');
    this.newValue.set('');
  }

  async reveal(file: string, key: string): Promise<void> {
    const r = await this.engine.envReveal(file, key);
    if (r.ok) this.revealed.update((v) => ({ ...v, [file + '/' + key]: r.value }));
    else this.error.set(r.error);
  }

  private async apply(call: Promise<{ ok: true; value: EnvOverview } | { ok: false; error: string }>): Promise<void> {
    this.error.set(null);
    const r = await call;
    if (r.ok) this.view.set(r.value);
    else this.error.set(r.error);
  }
}
