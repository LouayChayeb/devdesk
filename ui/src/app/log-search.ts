import { Component, OnInit, computed, inject, output, signal } from '@angular/core';
import { Engine } from './engine';
import { Icon } from './icon';
import { Autofocus } from './autofocus';

interface Found {
  at: string;
  service: string;
  stream: string;
  text: string;
}

/**
 * Searches everything the services printed in the last days (the engine keeps it). Plain text ignores case;
 * /regex/ is a regular expression. A result shows the lines around it, like opening the log at that moment.
 */
@Component({
  selector: 'dd-log-search',
  imports: [Icon, Autofocus],
  template: `
    <div class="scrim" (click)="close.emit()">
      <div class="panel" role="dialog" aria-label="Search logs" (click)="$event.stopPropagation()">
        <header>
          <h3>Search logs</h3>
          <span class="dim small">Everything the services printed, kept 7 days. Plain text, or /regex/.</span>
          <span class="grow"></span>
          <button class="ghost icon sm" title="Close (Esc)" (click)="close.emit()"><dd-icon name="close" [size]="14" /></button>
        </header>
        <div class="controls">
          <input class="mono grow" placeholder="e.g. Traceback, order 42, /5\\d\\d$/" [value]="text()" ddAutofocus
                 (input)="text.set($any($event.target).value); later()" (keydown.enter)="search()" (keydown.escape)="close.emit()" />
          <select [value]="service()" (change)="service.set($any($event.target).value); search()">
            <option value="">all services</option>
            @for (s of services(); track s) { <option [value]="s">{{ s }}</option> }
          </select>
          <select (change)="hours.set(+$any($event.target).value); search()">
            <option value="1" [selected]="hours() === 1">last hour</option>
            <option value="24" [selected]="hours() === 24">last 24 hours</option>
            <option value="168" [selected]="hours() === 168">last 7 days</option>
          </select>
          <button class="ghost" [class.on]="errorsOnly()" (click)="errorsOnly.set(!errorsOnly()); search()">Errors only</button>
        </div>
        @if (error(); as e) { <p class="problem small">{{ e }}</p> }

        <div class="split">
          <div class="results">
            <div class="dim small count">
              @if (busy()) { Searching… } @else if (searched()) {
                {{ found().length }}{{ more() ? '+' : '' }} line{{ found().length === 1 ? '' : 's' }}{{ more() ? ' (newest first; narrow the search to see older ones)' : '' }}
              }
            </div>
            @for (f of found(); track $index) {
              <button class="hit" [class.on]="picked() === f" [class.err]="f.stream === 'stderr'" (click)="pick(f)">
                <span class="mono dim when">{{ when(f.at) }}</span>
                <span class="mono svc">{{ f.service }}</span>
                <span class="mono text">{{ f.text }}</span>
              </button>
            } @empty {
              @if (searched() && !busy()) { <p class="dim small">Nothing found.</p> }
            }
          </div>
          @if (picked(); as p) {
            <div class="context">
              <div class="dim small">{{ p.service }} around {{ when(p.at) }}</div>
              <pre class="mono">@for (l of around(); track $index) {<span [class.mark]="l.at === p.at && l.text === p.text" [class.err]="l.stream === 'stderr'">{{ time(l.at) }}  {{ l.text }}
</span>}</pre>
            </div>
          }
        </div>
      </div>
    </div>
  `,
  styles: `
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); display: flex; align-items: center; justify-content: center; z-index: 30; }
    .panel { width: min(1180px, 95vw); height: min(780px, 88vh); display: flex; flex-direction: column; gap: 10px; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); padding: 16px 20px; }
    header { display: flex; align-items: baseline; gap: 10px; }
    h3 { margin: 0; font: 700 var(--fs-xl) var(--font-display); }
    .grow { flex: 1; }
    .controls { display: flex; gap: 6px; }
    input, select { height: 30px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 8px; }
    .split { flex: 1; display: flex; gap: 10px; min-height: 0; }
    .results { flex: 1; overflow: auto; min-width: 0; }
    .count { padding: 2px 0 6px; }
    .hit { display: flex; width: 100%; height: auto; justify-content: flex-start; align-items: baseline; gap: 10px; background: transparent; border: none; border-radius: var(--radius-badge); padding: 3px 6px; text-align: left; font-size: var(--fs-sm); }
    .hit:hover { background: var(--bg-2); }
    .hit.on { background: var(--accent-weak); }
    .when { flex: none; width: 150px; }
    .svc { flex: none; width: 90px; color: var(--accent); overflow: hidden; text-overflow: ellipsis; }
    .text { white-space: pre-wrap; word-break: break-word; }
    .hit.err .text, span.err { color: var(--err); }
    .context { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }
    pre { flex: 1; margin: 0; overflow: auto; font-size: var(--fs-sm); line-height: 1.5; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 8px 10px; white-space: pre-wrap; word-break: break-word; }
    .mark { background: var(--warn-bg); outline: 1px solid var(--warn); }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); margin: 0; }
  `,
})
export class LogSearch implements OnInit {
  readonly engine = inject(Engine);
  readonly close = output<void>();

  readonly text = signal('');
  readonly service = signal('');
  readonly hours = signal(24);
  readonly errorsOnly = signal(false);
  readonly found = signal<Found[]>([]);
  readonly more = signal(false);
  readonly busy = signal(false);
  readonly searched = signal(false);
  readonly error = signal<string | null>(null);
  readonly picked = signal<Found | null>(null);
  readonly around = signal<Found[]>([]);
  readonly services = computed(() => (this.engine.project()?.services ?? []).map((s) => s.name));
  private timer?: ReturnType<typeof setTimeout>;

  ngOnInit(): void {
    this.search(); // the newest lines first, before anything is typed
  }

  /** Searches shortly after typing stops, so each key press doesn't read days of logs. */
  later(): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search(), 300);
  }

  async search(): Promise<void> {
    clearTimeout(this.timer);
    this.busy.set(true);
    this.error.set(null);
    const r = await this.engine.searchLogs(this.text(), this.service(), this.errorsOnly(), this.hours());
    this.busy.set(false);
    this.searched.set(true);
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    this.found.set(r.value.lines);
    this.more.set(r.value.more);
    this.picked.set(null);
  }

  async pick(f: Found): Promise<void> {
    this.picked.set(f);
    const r = await this.engine.logsAround(f.service, f.at);
    this.around.set(r.ok ? r.value : []);
  }

  when(iso: string): string {
    return new Date(iso).toLocaleString('en-GB', { hour12: false, day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }

  time(iso: string): string {
    return new Date(iso).toLocaleTimeString('en-GB', { hour12: false });
  }
}
