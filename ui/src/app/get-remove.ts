import { Component, OnDestroy, OnInit, inject, output, signal } from '@angular/core';
import { Engine, GetStatus, RemovalPlan, RemovalReport } from './engine';

/** "Get it running": paste a project's link; the engine clones it, then the app opens the folder (the wizard if new). */
@Component({
  selector: 'dd-get-project',
  template: `
    <div class="scrim">
      <div class="panel" role="dialog" aria-label="Get a project from a link">
        <header><h3>Get a project from a link</h3></header>
        <div class="body">
          <p class="dim small">DevDesk downloads it into <span class="mono">~/DevDesk</span>, then shows what it would run. Nothing runs before you trust it.</p>
          <div class="row">
            <input class="mono grow" placeholder="https://github.com/owner/project" [value]="link()" (input)="link.set($any($event.target).value)" (keydown.enter)="start()" [disabled]="status()?.state === 'cloning'" autofocus />
            <button class="primary" (click)="start()" [disabled]="!link().trim() || status()?.state === 'cloning'">Get it</button>
          </div>
          @if (error(); as e) { <p class="problem">{{ e }}</p> }
          @if (status(); as s) {
            @if (s.lines.length) { <pre class="out">{{ s.lines.join('\\n') }}</pre> }
            @if (s.state === 'cloning') { <p class="dim small">Downloading…</p> }
          }
        </div>
        <footer><span class="grow"></span><button class="ghost" (click)="cancel.emit()">Close</button></footer>
      </div>
    </div>
  `,
  styles: `
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); display: flex; align-items: center; justify-content: center; z-index: 30; }
    .panel { width: min(720px, 94vw); max-height: 88vh; display: flex; flex-direction: column; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); }
    header { padding: 18px 22px 12px; border-bottom: 1px solid var(--border); }
    h3 { margin: 0; font: 700 var(--fs-xl) var(--font-display); }
    .body { flex: 1; overflow: auto; padding: 12px 22px 16px; }
    footer { display: flex; gap: 8px; padding: 12px 22px; border-top: 1px solid var(--border); }
    .row { display: flex; gap: 8px; }
    .grow { flex: 1; }
    input { height: 30px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 8px; }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); }
    .out { font: 400 var(--fs-xs) / 1.5 var(--font-mono); background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 8px; margin: 10px 0 0; white-space: pre-wrap; max-height: 260px; overflow: auto; }
  `,
})
export class GetProject implements OnDestroy {
  readonly engine = inject(Engine);
  readonly got = output<string>();
  readonly cancel = output<void>();
  readonly link = signal('');
  readonly status = signal<GetStatus | null>(null);
  readonly error = signal<string | null>(null);
  private timer: ReturnType<typeof setInterval> | undefined;

  async start(): Promise<void> {
    this.error.set(null);
    const r = await this.engine.getStart(this.link().trim());
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    this.status.set(r.value);
    this.timer = setInterval(() => this.poll(), 500);
  }

  private async poll(): Promise<void> {
    const r = await this.engine.getStatus();
    if (!r.ok) return;
    this.status.set(r.value);
    if (r.value.state === 'cloning') return;
    clearInterval(this.timer);
    if (r.value.state === 'done') this.got.emit(r.value.folder);
    else this.error.set(r.value.error);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }
}

/** "Remove": the engine's list of what DevDesk made for the project, a clear warning when data goes, then its report. */
@Component({
  selector: 'dd-remove-project',
  template: `
    <div class="scrim">
      <div class="panel" role="dialog" aria-label="Remove this project">
        <header><h3>Remove {{ plan()?.project ?? 'this project' }}</h3><span class="mono dim small">{{ plan()?.folder }}</span></header>
        <div class="body">
          @if (error(); as e) { <p class="problem">{{ e }}</p> }
          @if (report(); as r) {
            @if (r.clean) {
              <p class="ok-text">✓ Removed {{ r.removed.length }} thing{{ r.removed.length === 1 ? '' : 's' }}. Checked again: nothing of it is left.</p>
            } @else {
              @for (f of r.failed; track $index) { <p class="problem">✕ {{ f }}</p> }
              @if (r.leftovers.length) {
                <h4>Still there</h4>
                @for (i of r.leftovers; track $index) { <div class="item">{{ i.what }}</div> }
              }
            }
          } @else if (plan(); as p) {
            @if (!p.items.length) {
              <p class="dim">DevDesk made nothing for it that is still here.</p>
            } @else {
              <p>It stops the project, then deletes:</p>
              @for (i of p.items; track $index) { <div class="item" [class.data]="i.data">{{ i.what }}</div> }
              @for (k of p.kept; track $index) { <p class="dim small">Kept: {{ k }}</p> }
              @if (p.losesData) { <p class="warn-box">This deletes data too (marked above): database contents, and the downloaded folder with any changes you made in it. It can't be undone.</p> }
            }
          } @else if (!error()) {
            <p class="dim">Looking for what DevDesk made for it…</p>
          }
        </div>
        <footer>
          <span class="grow"></span>
          @if (report()) {
            <button class="primary" (click)="done.emit({ folder: plan()?.folder ?? '', gone: !!plan()?.cloned })">Close</button>
          } @else {
            <button class="ghost" (click)="cancel.emit()" [disabled]="busy()">Cancel</button>
            <button class="danger" (click)="remove()" [disabled]="busy() || !plan()?.items?.length">{{ busy() ? 'Removing…' : 'Remove everything listed' }}</button>
          }
        </footer>
      </div>
    </div>
  `,
  styles: `
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); display: flex; align-items: center; justify-content: center; z-index: 30; }
    .panel { width: min(720px, 94vw); max-height: 88vh; display: flex; flex-direction: column; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); }
    header { padding: 18px 22px 12px; border-bottom: 1px solid var(--border); display: flex; align-items: baseline; gap: 12px; }
    h3 { margin: 0; font: 700 var(--fs-xl) var(--font-display); }
    h4 { margin: 16px 0 8px; font-size: var(--fs-xs); color: var(--text-2); text-transform: uppercase; letter-spacing: 0.04em; }
    .body { flex: 1; overflow: auto; padding: 12px 22px 16px; }
    footer { display: flex; gap: 8px; padding: 12px 22px; border-top: 1px solid var(--border); }
    .grow { flex: 1; }
    .item { padding: 5px 10px; border: 1px solid var(--border); border-radius: var(--radius-badge); margin-bottom: 4px; font-size: var(--fs-sm); }
    .item.data { border-left: 2px solid var(--warn); }
    .warn-box { background: var(--warn-bg); border-left: 2px solid var(--warn); padding: 6px 10px; border-radius: var(--radius-badge); }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); }
    .ok-text { color: var(--ok); }
    button.danger { background: var(--err); color: #fff; border: none; border-radius: var(--radius-badge); padding: 0 12px; height: 30px; }
  `,
})
export class RemoveProject implements OnInit {
  readonly engine = inject(Engine);
  /** The folder, and whether it's gone (DevDesk had downloaded it): then it leaves the recent list too. */
  readonly done = output<{ folder: string; gone: boolean }>();
  readonly cancel = output<void>();
  readonly plan = signal<RemovalPlan | null>(null);
  readonly report = signal<RemovalReport | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  async ngOnInit(): Promise<void> {
    const r = await this.engine.removalPlan();
    if (r.ok) this.plan.set(r.value);
    else this.error.set(r.error);
  }

  async remove(): Promise<void> {
    this.busy.set(true);
    const r = await this.engine.removeProject().finally(() => this.busy.set(false));
    if (r.ok) this.report.set(r.value);
    else this.error.set(r.error);
  }
}
