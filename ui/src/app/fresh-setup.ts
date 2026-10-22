import { Component, OnDestroy, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { Engine, Finding, FreshPlan, FreshRun, FreshStep } from './engine';
import { Icon } from './icon';

/**
 * "Set up" for a fresh clone that already has a devdesk.yaml: the doctor first, then every step with its exact command,
 * then each step's progress. The engine plans and runs; this only shows it and collects the missing .env values.
 */
@Component({
  selector: 'dd-fresh-setup',
  imports: [Icon],
  template: `
    <div class="scrim">
      <div class="panel" role="dialog" aria-label="Set up this project">
        <header>
          <h3>Set up {{ plan()?.name ?? 'this project' }}</h3>
          <span class="mono dim small">{{ path() }}</span>
        </header>

        <div class="body">
          @if (error(); as e) { <p class="problem">{{ e }}</p> }
          @if (!plan()) {
            @if (!error()) { <p class="dim">Reading devdesk.yaml and the project's files…</p> }
          } @else if (!run()) {
            <h4>This machine</h4>
            @if (doctor(); as d) {
              @for (f of d.problems; track $index) {
                <div class="finding" [class.warn]="f.severity === 'warning'">
                  <div><span class="sev">{{ f.severity === 'error' ? '✕' : '!' }}</span> {{ f.title }}</div>
                  @if (f.actions.length) { <div class="mono small fix">→ {{ f.actions[0] }}</div> }
                </div>
              }
              @if (!d.problems.length) { <p class="ok-text small">✓ This machine has what the project needs.</p> }
              @else { <p class="dim small">Some of these are fixed by the steps below (packages, images, .env). Fix the others yourself.</p> }
            } @else {
              <p class="dim small">Checking versions, Docker, packages and disk…</p>
            }

            @for (q of questions(); track q.key) {
              @if ($first) { <h4>Values for .env</h4> <p class="dim small">The example file leaves these empty. They go only into .env (never into devdesk.yaml).</p> }
              <label class="question">
                <span class="mono">{{ q.key }}</span>
                <input class="mono" [type]="q.secret ? 'password' : 'text'" [placeholder]="q.example || 'value'" [value]="answers()[q.key] ?? ''" (input)="answer(q.key, $any($event.target).value)" />
              </label>
            }

            <h4>Steps</h4>
            @for (s of plan()!.steps; track s.id) {
              <label class="step">
                <input type="checkbox" [checked]="chosen().has(s.id)" (change)="toggle(s.id)" />
                <div>
                  <div>{{ s.title }}</div>
                  <div class="mono small dim">{{ describe(s) }}</div>
                </div>
              </label>
            } @empty {
              <p class="ok-text">Nothing to prepare: it's ready to open.</p>
            }
            @for (n of plan()!.notes; track $index) { <p class="note small">{{ n }}</p> }
          } @else {
            @for (s of run()!.steps; track s.id) {
              <div class="progress {{ s.status }}">
                <div class="line">
                  <span class="icon">{{ icon(s.status) }}</span>
                  <span class="grow">{{ s.title }}</span>
                  <span class="small dim">{{ s.detail || label(s.status) }}</span>
                  @if (s.output.length) {
                    <button class="ghost sm" (click)="toggleOpen(s.id)">{{ open().has(s.id) || s.status === 'running' || s.status === 'failed' ? 'Hide output' : 'Output' }}</button>
                  }
                </div>
                @if (s.output.length && (open().has(s.id) || s.status === 'running' || s.status === 'failed')) {
                  <pre class="out">{{ s.output.slice(-12).join('\\n') }}</pre>
                }
              </div>
            }
            @if (!run()!.running) {
              @if (allOk()) { <p class="ok-text">Done. The project is ready: open it and press Start all.</p> }
              @else { <p class="problem">A step failed: its output is above. Fix it, then run the setup again (finished steps are skipped).</p> }
            }
          }
        </div>

        <footer>
          @if (run()?.running) {
            <button class="danger" (click)="cancelRun()"><dd-icon name="stop" [size]="12" /> Stop the setup</button>
          } @else {
            <button class="ghost" (click)="cancel.emit()">Close</button>
          }
          <span class="grow"></span>
          @if (!run() && plan()) {
            @if (plan()!.steps.length) {
              <button class="primary" [disabled]="!chosen().size || busy()" (click)="start()"><dd-icon name="play" [size]="12" /> Run setup ({{ chosen().size }} step{{ chosen().size === 1 ? '' : 's' }})</button>
            } @else {
              <button class="primary" (click)="done.emit(path())">Open the project</button>
            }
          }
          @if (run() && !run()!.running) {
            @if (allOk()) {
              <button class="primary" (click)="done.emit(path())">Open the project</button>
            } @else {
              <button class="primary" (click)="reload()">Run the setup again</button>
            }
          }
        </footer>
      </div>
    </div>
  `,
  styles: `
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); display: flex; align-items: center; justify-content: center; z-index: 30; }
    .panel { width: min(860px, 94vw); max-height: 88vh; display: flex; flex-direction: column; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); }
    header { padding: 18px 22px 12px; border-bottom: 1px solid var(--border); display: flex; align-items: baseline; gap: 12px; }
    h3 { margin: 0; font: 700 var(--fs-xl) var(--font-display); }
    h4 { margin: 16px 0 8px; font-size: var(--fs-xs); color: var(--text-2); text-transform: uppercase; letter-spacing: 0.04em; }
    .body { flex: 1; overflow: auto; padding: 12px 22px 16px; }
    footer { display: flex; gap: 8px; padding: 12px 22px; border-top: 1px solid var(--border); }
    .grow { flex: 1; }
    .finding { border-left: 2px solid var(--err); background: var(--err-bg); padding: 6px 10px; border-radius: var(--radius-badge); margin-bottom: 6px; }
    .finding.warn { border-left-color: var(--warn); background: var(--warn-bg); }
    .sev { color: var(--err); font-weight: 600; }
    .finding.warn .sev { color: var(--warn); }
    .fix { color: var(--text-2); margin-top: 2px; }
    .question { display: grid; grid-template-columns: 220px 1fr; gap: 10px; align-items: center; margin-bottom: 6px; }
    input:not([type='checkbox']) { height: 28px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 8px; }
    .step { display: flex; gap: 10px; align-items: flex-start; padding: 8px 10px; border: 1px solid var(--border); border-radius: var(--radius); margin-bottom: 6px; cursor: pointer; }
    .step input { margin-top: 3px; }
    .note { background: var(--warn-bg); border-left: 2px solid var(--warn); padding: 6px 10px; border-radius: var(--radius-badge); }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); }
    .ok-text { color: var(--ok); }
    .progress { border: 1px solid var(--border); border-radius: var(--radius); padding: 8px 10px; margin-bottom: 6px; }
    .progress.failed { border-color: rgba(240, 100, 90, 0.5); background: var(--err-bg); }
    .progress.ok .icon { color: var(--ok); }
    .progress.failed .icon { color: var(--err); }
    .progress.running .icon { color: var(--accent); animation: pulse 1.2s ease-in-out infinite; }
    .line { display: flex; align-items: center; gap: 10px; }
    .icon { width: 16px; text-align: center; font-weight: 700; }
    .out { font: 400 var(--fs-xs) / 1.5 var(--font-mono); background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 8px; margin: 8px 0 0; white-space: pre-wrap; max-height: 220px; overflow: auto; }
    @keyframes pulse { 50% { opacity: 0.35; } }
  `,
})
export class FreshSetup implements OnInit, OnDestroy {
  readonly engine = inject(Engine);
  readonly path = input.required<string>();
  readonly done = output<string>();
  readonly cancel = output<void>();

  readonly plan = signal<FreshPlan | null>(null);
  readonly doctor = signal<{ problems: Finding[]; passed: string[] } | null>(null);
  readonly chosen = signal<Set<string>>(new Set());
  readonly answers = signal<Record<string, string>>({});
  readonly open = signal<Set<string>>(new Set());
  readonly run = signal<FreshRun | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  private timer: ReturnType<typeof setInterval> | null = null;

  /** The questions of the chosen .env steps: only what the example leaves empty. */
  readonly questions = computed(() =>
    (this.plan()?.steps ?? []).filter((s) => s.kind === 'env' && this.chosen().has(s.id)).flatMap((s) => s.questions ?? []),
  );
  readonly allOk = computed(() => !!this.run() && this.run()!.steps.every((s) => s.status === 'ok'));

  async ngOnInit(): Promise<void> {
    await this.reload();
  }

  ngOnDestroy(): void {
    if (this.timer) clearInterval(this.timer);
  }

  async reload(): Promise<void> {
    this.run.set(null);
    this.error.set(null);
    const r = await this.engine.freshPlan(this.path());
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    this.plan.set(r.value);
    this.chosen.set(new Set(r.value.steps.map((s) => s.id)));
    if (r.value.running) this.follow(); // a run started earlier is still going: show it
    const d = await this.engine.setupDoctor(this.path(), r.value.draft);
    if (d.ok) this.doctor.set(d.value);
  }

  describe(s: FreshStep): string {
    if (s.kind === 'env') return `writes ${s.file} (only readable by you)`;
    if (s.kind === 'job') return `starts ${s.service} with what it needs, waits until it finished, then stops everything`;
    return (s.directory ? `in ./${s.directory}: ` : '') + s.command;
  }

  toggle(id: string): void {
    this.chosen.update((set) => {
      const next = new Set(set);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  toggleOpen(id: string): void {
    this.open.update((set) => {
      const next = new Set(set);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  answer(key: string, value: string): void {
    this.answers.update((a) => ({ ...a, [key]: value }));
  }

  async start(): Promise<void> {
    this.busy.set(true);
    const ids = this.plan()!.steps.filter((s) => this.chosen().has(s.id)).map((s) => s.id);
    const r = await this.engine.freshRun(this.path(), ids, this.answers());
    this.busy.set(false);
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    this.run.set(r.value);
    this.follow();
  }

  private follow(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = setInterval(async () => {
      const r = await this.engine.freshStatus();
      if (!r.ok) return;
      this.run.set(r.value);
      if (!r.value.running && this.timer) {
        clearInterval(this.timer);
        this.timer = null;
      }
    }, 700);
  }

  async cancelRun(): Promise<void> {
    await this.engine.freshCancel();
  }

  icon(status: string): string {
    return { ok: '✓', failed: '✕', running: '●', skipped: '–', cancelled: '–', waiting: '○' }[status] ?? '○';
  }

  label(status: string): string {
    return { waiting: 'waiting', running: 'running…', skipped: 'skipped', cancelled: 'stopped' }[status] ?? status;
  }
}
