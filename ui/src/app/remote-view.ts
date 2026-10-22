import { Component, OnDestroy, OnInit, inject, output, signal } from '@angular/core';
import { Engine, RemoteView } from './engine';
import { Icon } from './icon';
import { openEngineWindow } from './files';

/**
 * Deploy, or a remote dev box: the project on another Linux machine over SSH. Shows each step the engine runs (plain
 * ssh and rsync commands) with its output, and opens the remote engine in its own window once it's connected.
 */
@Component({
  selector: 'dd-remote',
  imports: [Icon],
  template: `
    <div class="scrim" (click)="close.emit()">
      <div class="panel" role="dialog" aria-label="Another machine" (click)="$event.stopPropagation()">
        <header>
          <h3>Run it on another machine</h3>
          <span class="dim small">A server, a stronger box, or a local VM to try it. DevDesk uses your SSH keys (~/.ssh); nothing is installed there but its engine.</span>
          <span class="grow"></span>
          <button class="ghost icon sm" title="Close" (click)="close.emit()"><dd-icon name="close" [size]="14" /></button>
        </header>

        <div class="target">
          <input class="mono" placeholder="user" [value]="user()" (input)="user.set($any($event.target).value)" />
          <span class="dim">&#64;</span>
          <input class="mono host" placeholder="host or IP" [value]="host()" (input)="host.set($any($event.target).value)" />
          <span class="dim">port</span>
          <input class="mono num" type="number" [value]="port()" (input)="port.set(+$any($event.target).value)" />
          <span class="dim">folder</span>
          <input class="mono folder" placeholder="~/apps/shop" [value]="folder()" (input)="folder.set($any($event.target).value)" />
          <input class="mono key" placeholder="key file (optional)" title="A private key to log in with; empty = your usual SSH keys" [value]="identityFile()" (input)="identityFile.set($any($event.target).value)" />
          <button class="sm" (click)="save()">Save</button>
        </div>

        <div class="actions">
          <button class="primary sm" [disabled]="!view()?.target || view()?.running" (click)="run('deploy')" title="Copy it there and start it; it keeps running when you close DevDesk">
            <dd-icon name="upload" [size]="12" /> Deploy
          </button>
          <button class="sm" [disabled]="!view()?.target || view()?.running" (click)="run('devbox')" title="Run it there, with its ports here and your saves copied over as you work">
            <dd-icon name="terminal" [size]="12" /> Start dev box
          </button>
          @if (view()?.engine; as e) {
            <button class="sm" (click)="openRemote(e.port, e.token)"><dd-icon name="external" [size]="12" /> Open it</button>
            <button class="ghost sm" (click)="stop(false)" title="Close the connection; the project keeps running there">Disconnect</button>
          }
          @if (view()?.target) {
            <button class="ghost sm danger-text" (click)="stop(true)" title="Stop the project and DevDesk's engine on that machine">Stop it there</button>
          }
          <span class="grow"></span>
          @if (view()?.connected) {
            <span class="ok small">● connected{{ view()!.syncing ? ' · copying your saves' : '' }}</span>
          }
        </div>
        <div class="dim small">To try it for free: a local Ubuntu VM (<span class="mono">multipass launch --name devbox</span>, then its IP here), or <span class="mono">localhost</span> if this machine runs an SSH server. It needs Java 25, Docker (for containers) and rsync.</div>
        @if (error(); as e) { <p class="problem small">{{ e }}</p> }
        @if (view()?.forwards?.length) {
          <div class="small dim">Here on this machine: @for (f of view()!.forwards; track f.port) { <span class="mono fwd">{{ f.service }} → localhost:{{ f.port }}</span> }</div>
        }

        <div class="steps">
          @for (s of view()?.steps ?? []; track $index) {
            <div class="step {{ s.status }}">
              <div class="shead">
                <span class="icon">{{ icon(s.status) }}</span>
                <span>{{ s.name }}</span>
              </div>
              @if (s.command) { <div class="mono small dim cmd">$ {{ s.command }}</div> }
              @if (s.output.length && s.status !== 'ok') { <pre class="mono small">{{ s.output.join('\\n') }}</pre> }
            </div>
          }
        </div>
      </div>
    </div>
  `,
  styles: `
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); display: flex; align-items: center; justify-content: center; z-index: 30; }
    .panel { width: min(980px, 95vw); max-height: 88vh; display: flex; flex-direction: column; gap: 10px; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); padding: 16px 20px; overflow: auto; }
    header { display: flex; align-items: baseline; gap: 10px; }
    h3 { margin: 0; font: 700 var(--fs-xl) var(--font-display); }
    .grow { flex: 1; }
    .target, .actions { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
    input { height: 28px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 8px; width: 110px; }
    input.host { width: 180px; }
    input.num { width: 70px; }
    input.folder { width: 200px; }
    input.key { width: 200px; }
    .ok { color: var(--ok); }
    .danger-text { color: var(--err); }
    .fwd { margin-right: 10px; color: var(--text-1); }
    .steps { display: flex; flex-direction: column; gap: 6px; }
    .step { border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 6px 10px; background: var(--bg-0); }
    .shead { display: flex; gap: 8px; align-items: center; }
    .icon { width: 14px; text-align: center; font-weight: 700; }
    .step.ok .icon { color: var(--ok); }
    .step.failed .icon { color: var(--err); }
    .step.running .icon { color: var(--accent); }
    .step.waiting { opacity: 0.6; }
    .cmd { word-break: break-all; margin-top: 2px; }
    pre { margin: 4px 0 0; white-space: pre-wrap; word-break: break-word; max-height: 200px; overflow: auto; }
    .step.failed pre { color: var(--err); }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); margin: 0; }
  `,
})
export class RemoteViewComponent implements OnInit, OnDestroy {
  readonly engine = inject(Engine);
  readonly close = output<void>();
  readonly view = signal<RemoteView | null>(null);
  readonly user = signal('');
  readonly host = signal('');
  readonly port = signal(22);
  readonly folder = signal('');
  readonly identityFile = signal('');
  readonly error = signal<string | null>(null);
  private timer?: ReturnType<typeof setInterval>;

  async ngOnInit(): Promise<void> {
    await this.load(true);
    this.timer = setInterval(() => {
      if (this.view()?.running || this.view()?.connected) this.load(false);
    }, 1500);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private async load(fill: boolean): Promise<void> {
    const r = await this.engine.remote();
    if (!r.ok) return this.error.set(r.error);
    this.view.set(r.value);
    const t = r.value.target;
    if (fill && t) {
      this.user.set(t.user);
      this.host.set(t.host);
      this.port.set(t.port);
      this.folder.set(t.folder);
      this.identityFile.set(t.identityFile ?? '');
    }
  }

  async save(): Promise<void> {
    this.error.set(null);
    const r = await this.engine.setRemote({ user: this.user().trim(), host: this.host().trim(), port: this.port(), folder: this.folder().trim(), identityFile: this.identityFile().trim() });
    if (r.ok) this.view.set(r.value);
    else this.error.set(r.error);
  }

  async run(mode: 'deploy' | 'devbox'): Promise<void> {
    this.error.set(null);
    const r = await this.engine.runRemote(mode);
    if (r.ok) this.view.set(r.value);
    else this.error.set(r.error);
  }

  async stop(there: boolean): Promise<void> {
    if (there && !confirm('Stop the project and DevDesk\'s engine on that machine?')) return;
    const r = await this.engine.stopRemote(there);
    if (r.ok) this.view.set(r.value);
    else this.error.set(r.error);
  }

  openRemote(port: number, token: string): void {
    const t = this.view()?.target;
    openEngineWindow(port, token, t ? `${t.user}@${t.host}` : 'remote');
  }

  icon(status: string): string {
    return ({ ok: '✓', failed: '✕', running: '●', waiting: '○' } as Record<string, string>)[status] ?? '○';
  }
}
