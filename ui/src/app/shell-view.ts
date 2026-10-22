import { AfterViewInit, Component, ElementRef, OnDestroy, inject, input, output, signal, viewChild } from '@angular/core';
import type { Terminal } from '@xterm/xterm';
import type { FitAddon } from '@xterm/addon-fit';
import { Engine } from './engine';
import { Icon } from './icon';

/**
 * "Shell" on a service: a real terminal, in the service's folder with its environment, or inside its container.
 * Only a window onto the engine's terminal: the engine picks the command, checks trust and owns the process.
 */
@Component({
  selector: 'dd-shell',
  imports: [Icon],
  template: `
    <div class="scrim">
      <div class="panel" role="dialog" aria-label="Shell">
        <header>
          <dd-icon name="terminal" [size]="15" />
          <h3>Shell</h3>
          <span class="mono dim small title">{{ title() || service() }}</span>
          <span class="grow"></span>
          @if (state() === 'exited' || state() === 'refused') {
            <button class="sm" (click)="connect()"><dd-icon name="restart" [size]="12" /> Open again</button>
          }
          <button class="ghost icon sm" title="Close the shell (ends what runs in it)" (click)="close.emit()">
            <dd-icon name="close" [size]="14" />
          </button>
        </header>
        @if (problem(); as p) { <p class="problem small">{{ p }}</p> }
        <div class="term" #host (click)="term?.focus()"></div>
        <div class="dim small foot">
          @switch (state()) {
            @case ('connecting') { Opening… }
            @case ('open') { Closing this window ends the shell and what you started in it. Esc goes to the shell. }
            @case ('exited') { The shell ended. }
            @default { }
          }
        </div>
      </div>
    </div>
  `,
  styles: `
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); display: flex; align-items: center; justify-content: center; z-index: 30; }
    .panel { width: min(1180px, 95vw); height: min(780px, 88vh); display: flex; flex-direction: column; gap: 10px; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); padding: 16px 20px; }
    header { display: flex; align-items: center; gap: 10px; }
    h3 { margin: 0; font: 700 var(--fs-xl) var(--font-display); }
    .title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .grow { flex: 1; }
    .term { flex: 1; min-height: 0; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 6px 4px 6px 8px; }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); margin: 0; }
    .foot { min-height: 16px; }
  `,
})
export class ShellView implements AfterViewInit, OnDestroy {
  readonly engine = inject(Engine);
  readonly service = input.required<string>();
  readonly close = output<void>();

  readonly state = signal<'connecting' | 'open' | 'exited' | 'refused'>('connecting');
  readonly title = signal('');
  readonly problem = signal<string | null>(null);

  private readonly host = viewChild.required<ElementRef<HTMLElement>>('host');
  term?: Terminal;
  private fit?: FitAddon;
  private socket?: WebSocket;
  private resizes?: ResizeObserver;

  async ngAfterViewInit(): Promise<void> {
    // Loaded on first use: the terminal library is big, and most sessions never open a shell.
    const [{ Terminal }, { FitAddon }] = await Promise.all([import('@xterm/xterm'), import('@xterm/addon-fit')]);
    const css = getComputedStyle(document.documentElement);
    const v = (name: string) => css.getPropertyValue(name).trim();
    this.term = new Terminal({
      fontFamily: v('--font-mono'),
      fontSize: 13,
      cursorBlink: true,
      scrollback: 5000,
      theme: { background: v('--bg-0'), foreground: v('--text-1'), cursor: v('--accent'), selectionBackground: v('--accent-line') },
    });
    this.fit = new FitAddon();
    this.term.loadAddon(this.fit);
    this.term.open(this.host().nativeElement);
    this.fit.fit();
    this.term.onData((data) => this.send({ type: 'input', data }));
    this.term.onResize(({ cols, rows }) => this.send({ type: 'resize', columns: cols, rows }));
    this.resizes = new ResizeObserver(() => this.fit?.fit());
    this.resizes.observe(this.host().nativeElement);
    this.connect();
  }

  ngOnDestroy(): void {
    this.resizes?.disconnect();
    this.socket?.close(); // the engine then ends the shell
    this.term?.dispose();
  }

  connect(): void {
    const term = this.term;
    if (!term) return;
    this.socket?.close();
    this.problem.set(null);
    this.state.set('connecting');
    const url = this.engine.terminalUrl(this.service(), term.cols, term.rows);
    if (!url) {
      this.problem.set('Not connected to the engine.');
      this.state.set('refused');
      return;
    }
    term.reset();
    const ws = new WebSocket(url);
    ws.binaryType = 'arraybuffer';
    this.socket = ws;
    ws.onmessage = (m) => {
      if (m.data instanceof ArrayBuffer) {
        term.write(new Uint8Array(m.data)); // raw bytes: colors and cursor moves, as the shell printed them
        return;
      }
      const e = JSON.parse(m.data);
      if (e.type === 'opened') {
        this.title.set(e.title);
        this.state.set('open');
        term.focus();
      } else if (e.type === 'refused') {
        this.problem.set(`Can't open a shell: ${e.message}.`);
        this.state.set('refused');
      } else if (e.type === 'exited') {
        term.write(`\r\n\x1b[2m[the shell exited${e.code === null ? '' : ` with code ${e.code}`}]\x1b[0m\r\n`);
        this.state.set('exited');
      }
    };
    ws.onclose = () => {
      if (this.socket === ws && this.state() !== 'refused') this.state.set('exited');
    };
  }

  private send(message: object): void {
    if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(message));
  }
}
