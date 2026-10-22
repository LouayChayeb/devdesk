import { Component, OnDestroy, computed, effect, inject, signal, untracked } from '@angular/core';
import { Engine, TaskRun, TaskView } from './engine';
import { Icon } from './icon';

/**
 * The project's tasks (seed, test, reset-db) as buttons above the services list, and a window with a task's live
 * output and its recent runs. The engine runs them; this only starts, stops and shows.
 */
@Component({
  selector: 'dd-tasks',
  imports: [Icon],
  template: `
    @if (tasks().length) {
      <div class="bar">
        <span class="label">Tasks</span>
        @for (t of tasks(); track t.name) {
          <button class="sm task {{ t.last?.status ?? '' }}" [title]="tooltip(t)" (click)="click(t)">
            @switch (t.last?.status) {
              @case ('running') { <span class="spin"></span> }
              @case ('waiting') { <span class="spin"></span> }
              @case ('ok') { <span class="ok">✓</span> }
              @case ('failed') { <span class="err">✕</span> }
              @case ('blocked') { <span class="err">✕</span> }
              @default { <dd-icon name="play" [size]="11" /> }
            }
            {{ t.name }}
          </button>
        }
      </div>
    }

    @if (open(); as t) {
      <div class="scrim" (click)="close()">
        <div class="panel" role="dialog" [attr.aria-label]="'Task ' + t.name" (click)="$event.stopPropagation()">
          <header>
            <h3>{{ t.name }}</h3>
            <span class="dim small">{{ t.description }}</span>
            <span class="grow"></span>
            <button class="ghost icon sm" title="Close" (click)="close()"><dd-icon name="close" [size]="14" /></button>
          </header>
          <div class="cmd mono small">
            <span class="dim">{{ t.directory ? './' + t.directory + ' $' : '$' }}</span> {{ t.command }}
            @if (t.needs.length) { <div class="dim">needs {{ t.needs.join(', ') }}: started first if they aren't ready</div> }
          </div>

          @if (current(); as r) {
            <div class="status {{ r.status }}">
              <span class="icon">{{ icon(r.status) }}</span>
              <span>{{ label(r.status) }}</span>
              <span class="dim small">{{ r.detail }}</span>
              <span class="grow"></span>
              <span class="dim small mono">{{ r.seconds.toFixed(1) }} s</span>
            </div>
            <pre class="out">{{ (r.output ?? []).join('\\n') || '(no output yet)' }}</pre>
          } @else {
            <p class="dim">Not run yet.</p>
          }

          @if (runs().length > 1) {
            <h4>Earlier runs</h4>
            <div class="history">
              @for (r of runs(); track r.id) {
                <button class="ghost sm run" [class.on]="r.id === current()?.id" (click)="selected.set(r.id)">
                  <span class="{{ r.status === 'ok' ? 'ok' : r.status === 'running' || r.status === 'waiting' ? '' : 'err' }}">{{ icon(r.status) }}</span>
                  {{ when(r.started) }} · {{ r.seconds.toFixed(1) }} s
                </button>
              }
            </div>
          }
          @if (error(); as e) { <p class="problem small">{{ e }}</p> }

          <footer>
            @if (active()) {
              <button class="danger" (click)="cancel(t.name)"><dd-icon name="stop" [size]="12" /> Stop</button>
            } @else {
              <button class="primary" (click)="run(t)"><dd-icon name="play" [size]="12" /> {{ runs().length ? 'Run again' : 'Run' }}</button>
            }
          </footer>
        </div>
      </div>
    }
  `,
  styles: `
    .bar { display: flex; align-items: center; gap: 6px; padding: 8px 16px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
    .label { font-size: var(--fs-xs); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin-right: 4px; }
    .task { font-family: var(--font-mono); }
    .ok { color: var(--ok); font-weight: 600; }
    .err { color: var(--err); font-weight: 600; }
    .spin { width: 10px; height: 10px; border-radius: 50%; border: 2px solid var(--accent); border-right-color: transparent; animation: spin 0.8s linear infinite; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); display: flex; align-items: center; justify-content: center; z-index: 30; }
    .panel { width: min(860px, 94vw); max-height: 86vh; display: flex; flex-direction: column; gap: 10px; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); padding: 16px 20px; }
    header { display: flex; align-items: baseline; gap: 10px; }
    h3 { margin: 0; font: 700 var(--fs-xl) var(--font-mono); }
    h4 { margin: 4px 0 0; font-size: var(--fs-xs); color: var(--text-2); text-transform: uppercase; letter-spacing: 0.04em; }
    .grow { flex: 1; }
    .cmd { background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 8px 10px; }
    .status { display: flex; align-items: center; gap: 8px; }
    .status .icon { font-weight: 700; width: 14px; text-align: center; }
    .status.ok .icon { color: var(--ok); }
    .status.failed .icon, .status.blocked .icon { color: var(--err); }
    .status.running .icon, .status.waiting .icon { color: var(--accent); }
    .out { flex: 1; min-height: 160px; max-height: 46vh; overflow: auto; margin: 0; font: 400 var(--fs-sm) / 1.5 var(--font-mono); background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 8px 10px; white-space: pre-wrap; }
    .history { display: flex; gap: 4px; flex-wrap: wrap; }
    .run { font-family: var(--font-mono); }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); }
    footer { display: flex; justify-content: flex-end; }
  `,
})
export class TasksBar implements OnDestroy {
  readonly engine = inject(Engine);
  readonly tasks = signal<TaskView[]>([]);
  readonly open = signal<TaskView | null>(null);
  readonly runs = signal<TaskRun[]>([]);
  readonly selected = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  private timer: ReturnType<typeof setInterval>;

  /** The run shown: the one clicked in the history, else the newest. */
  readonly current = computed(() => this.runs().find((r) => r.id === this.selected()) ?? this.runs()[0] ?? null);
  readonly active = computed(() => ['running', 'waiting'].includes(this.runs()[0]?.status ?? ''));

  constructor() {
    // Reload when the project (or its devdesk.yaml) changes; refresh while something runs so the buttons stay true.
    effect(() => {
      this.engine.project();
      untracked(() => this.refresh());
    });
    this.timer = setInterval(() => {
      if (this.open() || this.tasks().some((t) => ['running', 'waiting'].includes(t.last?.status ?? ''))) {
        this.refresh();
      }
    }, 800);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  async refresh(): Promise<void> {
    if (!this.engine.project()) {
      this.tasks.set([]);
      return;
    }
    const r = await this.engine.tasks();
    this.tasks.set(r.ok ? r.value : []);
    const open = this.open();
    if (open) {
      const runs = await this.engine.taskRuns(open.name);
      if (runs.ok) this.runs.set(runs.value);
    }
  }

  tooltip(t: TaskView): string {
    return [t.description, t.command, t.last ? `last run: ${t.last.status} (${t.last.detail})` : 'not run yet']
      .filter(Boolean)
      .join('\n');
  }

  /** A click runs the task right away (after its warning, if it has one) and shows its output. */
  async click(t: TaskView): Promise<void> {
    this.open.set(t);
    this.selected.set(null);
    this.error.set(null);
    const runs = await this.engine.taskRuns(t.name);
    this.runs.set(runs.ok ? runs.value : []);
    if (!['running', 'waiting'].includes(t.last?.status ?? '')) await this.run(t);
  }

  async run(t: TaskView): Promise<void> {
    if (t.confirm && !confirm(`${t.name}: ${t.confirm}\n\nRun it?`)) return;
    this.error.set(null);
    this.selected.set(null);
    const r = await this.engine.runTask(t.name);
    if (!r.ok) this.error.set(r.error);
    await this.refresh();
  }

  async cancel(name: string): Promise<void> {
    await this.engine.cancelTask(name);
    await this.refresh();
  }

  close(): void {
    this.open.set(null);
  }

  icon(status: string): string {
    return { ok: '✓', failed: '✕', blocked: '✕', cancelled: '–', running: '●', waiting: '●' }[status] ?? '○';
  }

  label(status: string): string {
    return {
      ok: 'Finished',
      failed: 'Failed',
      blocked: 'Could not start',
      cancelled: 'Stopped',
      running: 'Running…',
      waiting: 'Waiting for the services it needs…',
    }[status] ?? status;
  }

  when(iso: string): string {
    return new Date(iso).toLocaleTimeString('en-GB', { hour12: false });
  }
}
