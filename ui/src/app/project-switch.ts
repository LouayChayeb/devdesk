import { Component, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { Engine } from './engine';
import { StatusDot } from './status-dot';
import { Autofocus } from './autofocus';

/**
 * Ctrl+O: jump to another recent project with the keyboard. Only one project runs at a time, so picking another one
 * while this one runs asks first, then the engine stops this one and opens the other in one go.
 */
@Component({
  selector: 'dd-project-switch',
  imports: [StatusDot, Autofocus],
  template: `
    <div class="scrim" (click)="close.emit()">
      <div class="box" role="dialog" aria-label="Switch project" (click)="$event.stopPropagation()">
        @if (confirming(); as path) {
          <div class="ask">
            <p><b>{{ current()?.name }}</b> is running. Only one project runs at a time.</p>
            <p class="dim small">Its services are stopped (each gets its usual time to shut down), then <b>{{ base(path) }}</b> opens. Nothing starts until you press Start.</p>
            <div class="buttons">
              <button class="ghost" (click)="confirming.set(null)">Back</button>
              <button class="primary" ddAutofocus (click)="go(path, true)" (keydown.escape)="confirming.set(null)">Stop {{ current()?.name }} and open {{ base(path) }}</button>
            </div>
          </div>
        } @else {
          <input class="mono" placeholder="Switch project: type a name or a path…" [value]="query()" ddAutofocus
                 (input)="query.set($any($event.target).value); index.set(0)" (keydown)="key($event)" />
          <div class="list">
            @for (p of matches(); track p; let i = $index) {
              <div class="item" [class.on]="i === index()" (mouseenter)="index.set(i)" (click)="pick(p)" [title]="p">
                <dd-dot [label]="p === current()?.root ? summary() : 'stopped'" />
                <span class="name">{{ base(p) }}</span>
                @if (p === current()?.root) { <span class="tag">open{{ engine.running() ? ' · running' : '' }}</span> }
                <span class="path mono">{{ short(p) }}</span>
                @if (p !== current()?.root) {
                  <button class="ghost icon sm forget" title="Remove from this list (the folder stays)" (click)="$event.stopPropagation(); forget.emit(p)">×</button>
                }
              </div>
            } @empty {
              @if (query().includes('/')) {
                <div class="none">Enter opens {{ query() }}</div>
              } @else {
                <div class="none">No project matches. Type a folder path to open a new one.</div>
              }
            }
          </div>
          <div class="foot dim small"><kbd>↑</kbd><kbd>↓</kbd> choose · <kbd>Enter</kbd> open · <kbd>Esc</kbd> close</div>
        }
      </div>
    </div>
  `,
  styles: `
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); display: flex; justify-content: center; align-items: flex-start; padding-top: 12vh; z-index: 20; }
    .box { width: min(640px, 90vw); background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); overflow: hidden; }
    input { width: 100%; box-sizing: border-box; border: none; border-bottom: 1px solid var(--border); border-radius: 0; padding: 12px 14px; font-size: var(--fs-md); background: var(--bg-1); }
    input:focus { outline: none; }
    .list { max-height: 50vh; overflow: auto; padding: 4px; }
    .item { display: flex; align-items: center; gap: 10px; padding: 7px 10px; border-radius: var(--radius); cursor: pointer; }
    .item.on { background: var(--bg-3); }
    .name { color: var(--text-1); font-weight: 600; }
    .tag { font-size: var(--fs-xs); color: var(--accent); }
    .path { flex: 1; text-align: right; color: var(--text-3); font-size: var(--fs-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .forget { visibility: hidden; color: var(--text-3); }
    .item:hover .forget { visibility: visible; }
    .none { color: var(--text-3); padding: 10px; font-size: var(--fs-sm); }
    .foot { border-top: 1px solid var(--border); padding: 6px 12px; display: flex; gap: 4px; align-items: center; }
    .ask { padding: 16px 18px; }
    .ask p { margin: 0 0 8px; }
    .buttons { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }
  `,
})
export class ProjectSwitch implements OnInit {
  readonly engine = inject(Engine);
  readonly projects = input.required<string[]>();
  /** The open project's overall state ("healthy", "unhealthy"…), for its dot. */
  readonly summary = input.required<string>();
  /** A project already picked elsewhere (the project menu) while this one runs: start on the question. */
  readonly ask = input<string | null>(null);
  readonly opened = output<{ path: string; stopCurrent: boolean }>();
  readonly forget = output<string>();
  readonly close = output<void>();

  readonly query = signal('');
  readonly index = signal(0);
  /** The project picked while the open one runs: waiting for "Stop … and open …". */
  readonly confirming = signal<string | null>(null);
  readonly current = computed(() => this.engine.project());

  ngOnInit(): void {
    this.confirming.set(this.ask());
  }

  /** The open project last: the switcher is for going somewhere else. */
  readonly matches = computed(() => {
    const q = this.query().trim().toLowerCase();
    const root = this.current()?.root;
    const list = [...this.projects().filter((p) => p !== root), ...this.projects().filter((p) => p === root)];
    return q ? list.filter((p) => p.toLowerCase().includes(q)) : list;
  });

  key(e: KeyboardEvent): void {
    const n = this.matches().length;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      this.index.set(n ? (this.index() + 1) % n : 0);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      this.index.set(n ? (this.index() - 1 + n) % n : 0);
    } else if (e.key === 'Enter') {
      const p = this.matches()[this.index()] ?? (this.query().includes('/') ? this.query().trim() : null);
      if (p) this.pick(p);
    } else if (e.key === 'Escape') {
      this.close.emit();
    }
  }

  pick(path: string): void {
    if (path === this.current()?.root) {
      this.close.emit(); // already open
    } else if (this.engine.running()) {
      this.confirming.set(path);
    } else {
      this.go(path, false);
    }
  }

  go(path: string, stopCurrent: boolean): void {
    this.opened.emit({ path, stopCurrent });
  }

  base(path: string): string {
    return path.split('/').filter(Boolean).pop() ?? path;
  }

  short(path: string): string {
    return path.replace(/^\/home\/[^/]+/, '~');
  }
}
