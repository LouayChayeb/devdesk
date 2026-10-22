import { Component, OnDestroy, effect, inject, input, output, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { Engine, ServiceDetails } from './engine';
import { StatusDot } from './status-dot';
import { Icon } from './icon';
import { bytes, cpu, time } from './format';

type Section = 'overview' | 'health' | 'resources' | 'ports' | 'environment' | 'dependencies';

/** DESIGN.md §4.4: everything about one service, as the engine reports it. Values of variables only on request. */
@Component({
  selector: 'dd-drawer',
  imports: [StatusDot, Icon, NgTemplateOutlet],
  template: `
    @if (d(); as d) {
      <header>
        <dd-dot [label]="d.service.state.label" />
        <span class="title">{{ d.service.name }}</span>
        <span class="pill {{ d.service.state.label }}">{{ d.service.state.label }}</span>
        <span class="grow"></span>
        <button class="ghost icon sm" title="Close (Esc)" (click)="close.emit()"><dd-icon name="close" [size]="15" /></button>
      </header>
      <div class="actions">
        @if (running(d)) {
          <button class="sm" (click)="act('restart')"><dd-icon name="restart" [size]="13" /> Restart</button>
          <button class="sm danger" (click)="act('stop')"><dd-icon name="stop" [size]="10" /> Stop</button>
        } @else {
          <button class="sm primary" (click)="act('start')"><dd-icon name="play" [size]="11" /> Start</button>
        }
        @if (d.service.kind === 'process' && watch(); as w) {
          <button class="sm" [class.on]="w.on" (click)="toggleWatch(!w.on)"
                  [title]="w.on ? 'Restarts when these change: ' + w.folders.join(', ') + '. Click to stop watching.' : 'Restart this service by itself when you save its files'">
            <dd-icon name="restart" [size]="12" /> {{ w.on ? 'Restarts on save: ' + w.folders.join(', ') : 'Restart on save' }}
          </button>
        }
        @if (d.service.kind !== 'container' || running(d)) {
          <button class="sm" (click)="shell.emit(d.service.name)"
                  [title]="d.service.kind === 'container' ? 'A terminal inside the running container' : 'A terminal in its folder, with the environment it gets'">
            <dd-icon name="terminal" [size]="13" /> Shell
          </button>
        }
        @if (d.service.tools?.includes('data')) {
          <button class="sm" (click)="data.emit(d.service.name)"><dd-icon name="box" [size]="13" /> Open data</button>
        }
        @if (error(); as e) { <span class="err">{{ e }}</span> }
      </div>

      <section>
        <h4 (click)="toggle('overview')"><dd-icon [name]="open('overview') ? 'chevronDown' : 'chevronRight'" [size]="13" /> Overview</h4>
        @if (open('overview')) {
          <dl class="mono">
            <dt>state</dt><dd>{{ d.service.state.label }}@if (d.since) { since {{ time(d.since) }} }@if (d.service.state.detail) { · {{ d.service.state.detail }} }</dd>
            <dt>{{ d.service.kind === 'container' ? 'image' : 'command' }}</dt><dd>{{ d.service.workload }}</dd>
            <dt>PIDs</dt><dd>{{ d.pids.length ? d.pids.join(', ') : '–' }}</dd>
            <dt>directory</dt><dd>{{ short(d.service.directory) }}</dd>
            <dt>restart</dt><dd>{{ d.service.restart }}</dd>
          </dl>
        }
      </section>

      <section>
        <h4 (click)="toggle('health')"><dd-icon [name]="open('health') ? 'chevronDown' : 'chevronRight'" [size]="13" /> Health</h4>
        @if (open('health')) {
          <div class="mono dim">{{ d.service.health === 'none' ? 'no health check: "running" means the process exists' : d.service.health }}</div>
          @if (d.probes.length) {
            <div class="ticks">
              @for (p of d.probes; track $index) { <span class="tick" [class.bad]="!p.ok" [title]="p.detail"></span> }
            </div>
          }
          @if (d.lastError) { <div class="mono err small">last failure: {{ d.lastError }}</div> }
        }
      </section>

      <section>
        <h4 (click)="toggle('resources')"><dd-icon [name]="open('resources') ? 'chevronDown' : 'chevronRight'" [size]="13" /> Resources <span class="dim">({{ running(d) ? 'last 5 min' : 'last run' }})</span></h4>
        @if (open('resources')) {
          @if (d.resources.length < 2) {
            <div class="dim small">No samples yet. DevDesk measures every 2 s while the service runs.</div>
          } @else {
            @for (c of charts(d); track c.label) {
              <div class="chart">
                <span class="clabel">{{ c.label }}</span>
                <svg viewBox="0 0 100 24" preserveAspectRatio="none"><polyline [attr.points]="c.points" /></svg>
                <span class="mono cval">{{ c.last }}</span>
              </div>
            }
          }
        }
      </section>

      <section>
        <h4 (click)="toggle('ports')"><dd-icon [name]="open('ports') ? 'chevronDown' : 'chevronRight'" [size]="13" /> Ports</h4>
        @if (open('ports')) {
          <dl class="mono">
            <dt>declared</dt><dd>{{ d.ports.declared.length ? d.ports.declared.join(', ') : '–' }}</dd>
            <dt>listening</dt>
            <dd>{{ d.ports.listening === null ? 'not measured (stopped, or inside a container)' : d.ports.listening.length ? d.ports.listening.join(', ') : 'none' }}</dd>
          </dl>
          @for (p of d.ports.notListening; track p) { <div class="warn small">⚠ declared {{ p }}, but nothing of {{ d.service.name }} listens on it</div> }
          @for (p of d.ports.undeclared; track p) { <div class="dim small">listens on {{ p }}, which devdesk.yaml doesn't declare</div> }
        }
      </section>

      <section>
        <h4 (click)="toggle('environment')"><dd-icon [name]="open('environment') ? 'chevronDown' : 'chevronRight'" [size]="13" /> Environment <span class="dim">({{ d.environment.length }})</span></h4>
        @if (open('environment')) {
          <div class="dim small">Values stay hidden. Show one only when you need it.</div>
          <input class="env-filter mono" placeholder="Filter variables…" [value]="envFilter()" (input)="envFilter.set($any($event.target).value)" />
          @let groups = envGroups(d);
          @if (groups.own.length) {
            <div class="group-label">Set for {{ d.service.name }}</div>
            <table class="env mono">
              @for (v of groups.own; track v.key) {
                <ng-container *ngTemplateOutlet="row; context: { $implicit: v, service: d.service.name }" />
              }
            </table>
          } @else if (!envFilter()) {
            <div class="dim small">devdesk.yaml sets no variables for {{ d.service.name }}.</div>
          }
          @if (groups.shell.length) {
            <button class="ghost sm group-toggle" (click)="shellOpen.set(!shellOpen())">
              <dd-icon [name]="shellOpen() || envFilter() ? 'chevronDown' : 'chevronRight'" [size]="13" />
              {{ groups.shell.length }} inherited from your shell
            </button>
            @if (shellOpen() || envFilter()) {
              <table class="env mono">
                @for (v of groups.shell; track v.key) {
                  <ng-container *ngTemplateOutlet="row; context: { $implicit: v, service: d.service.name }" />
                }
              </table>
            }
          }
          <ng-template #row let-v let-service="service">
            <tr>
              <td class="key">@if (v.secret) {<span class="lock" title="looks like a secret">●</span>}{{ v.key }}</td>
              <td class="src">{{ v.source }}</td>
              <td class="val">
                @if (revealed()[v.key] !== undefined) {
                  <span class="value">{{ revealed()[v.key] }}</span>
                  <button class="ghost icon sm" title="Hide" (click)="hide(v.key)"><dd-icon name="eyeOff" [size]="13" /></button>
                } @else {
                  <span class="dim">••••••</span>
                  <button class="ghost icon sm" title="Show this value" (click)="show(service, v.key)"><dd-icon name="eye" [size]="13" /></button>
                }
              </td>
            </tr>
          </ng-template>
        }
      </section>

      <section>
        <h4 (click)="toggle('dependencies')"><dd-icon [name]="open('dependencies') ? 'chevronDown' : 'chevronRight'" [size]="13" /> Dependencies</h4>
        @if (open('dependencies')) {
          <dl class="mono">
            <dt>depends on</dt>
            <dd>@for (n of d.dependsOn; track n) { <button class="chip" (click)="pick.emit(n)">{{ n }}</button> } @empty { – }</dd>
            <dt>needed by</dt>
            <dd>@for (n of d.neededBy; track n) { <button class="chip" (click)="pick.emit(n)">{{ n }}</button> } @empty { – }</dd>
          </dl>
        }
      </section>
    }
  `,
  styles: `
    :host { display: block; width: 420px; border-radius: 0; height: 100%; overflow: auto; background: var(--bg-1); border-left: 1px solid var(--border-strong); box-shadow: var(--shadow-float); padding: 12px 16px; }
    header { display: flex; align-items: center; gap: 8px; }
    .title { font: 600 var(--fs-lg) var(--font-ui); }
    .grow { flex: 1; }
    .actions { display: flex; gap: 8px; align-items: center; margin: 10px 0 4px; }
    section { border-top: 1px solid var(--border); padding: 8px 0; }
    h4 { display: flex; align-items: center; gap: 4px; font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin: 0 0 6px; cursor: pointer; user-select: none; transition: color var(--ease); }
    h4:hover { color: var(--text-1); }
    .pill { display: inline-flex; align-items: center; height: 20px; padding: 0 8px; border-radius: 999px; font-size: var(--fs-sm); background: var(--bg-2); color: var(--text-2); }
    .pill.healthy, .pill.running, .pill.completed { color: var(--ok); background: var(--ok-bg); }
    .pill.starting, .pill.waiting, .pill.blocked, .pill.stopping { color: var(--warn); background: var(--warn-bg); }
    .pill.unhealthy, .pill.crashed, .pill.crash-loop, .pill.failed { color: var(--err); background: var(--err-bg); }
    .chip { height: 22px; padding: 0 9px; border-radius: 999px; font: 500 var(--fs-sm) var(--font-mono); margin: 0 4px 4px 0; }
    section > *:not(h4) { animation: rise 160ms ease-out; }
    .env tr:hover td { background: var(--bg-2); }
    .env-filter { width: 100%; height: 26px; margin: 6px 0 2px; font-size: var(--fs-sm); }
    .group-label { font-size: var(--fs-xs); color: var(--text-3); margin: 8px 0 2px; }
    .group-toggle { margin-top: 8px; padding-left: 2px; }
    dl { display: grid; grid-template-columns: 90px 1fr; gap: 3px 8px; margin: 0; font-size: var(--fs-sm); }
    dt { color: var(--text-3); }
    dd { margin: 0; word-break: break-word; }
    .dim { color: var(--text-3); }
    .small { font-size: var(--fs-sm); margin-top: 4px; }
    .err { color: var(--err); }
    .warn { color: var(--warn); }
    .label.healthy, .label.running, .label.completed { color: var(--ok); }
    .label.starting, .label.waiting, .label.blocked, .label.stopping { color: var(--warn); }
    .label.unhealthy, .label.crashed, .label.crash-loop, .label.failed { color: var(--err); }
    .label.stopped { color: var(--text-3); }
    .ticks { display: flex; gap: 2px; margin-top: 6px; }
    .tick { width: 6px; height: 16px; border-radius: 2px; background: var(--ok); opacity: 0.85; transition: transform var(--ease); }
    .tick:hover { transform: scaleY(1.25); opacity: 1; }
    .tick.bad { background: var(--err); }
    .chart { display: flex; align-items: center; gap: 8px; margin: 4px 0; }
    .clabel { width: 60px; color: var(--text-2); font-size: var(--fs-sm); }
    .chart svg { flex: 1; height: 24px; background: var(--bg-0); border-radius: 2px; }
    .chart polyline { fill: none; stroke: var(--accent); stroke-width: 1.2; vector-effect: non-scaling-stroke; }
    .cval { width: 60px; text-align: right; font-size: var(--fs-sm); font-variant-numeric: tabular-nums; }
    .env { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); margin-top: 4px; }
    .env td { padding: 2px 4px 2px 0; vertical-align: top; }
    .key { word-break: break-all; }
    .lock { color: var(--warn); margin-right: 4px; font-size: 8px; vertical-align: middle; }
    .src { color: var(--text-3); white-space: nowrap; }
    .val { white-space: nowrap; }
    .value { white-space: normal; word-break: break-all; }
    .tiny { padding: 0 6px; font-size: var(--fs-xs); }
    .icon { padding: 1px 7px; }
    a { color: var(--accent); cursor: pointer; margin-right: 8px; }
  `,
})
export class ServiceDrawer implements OnDestroy {
  readonly engine = inject(Engine);
  readonly service = input.required<string>();
  readonly close = output<void>();
  readonly pick = output<string>();
  readonly data = output<string>();
  readonly shell = output<string>();
  readonly action = output<{ service: string; action: 'start' | 'stop' | 'restart' }>();
  readonly error = signal<string | null>(null);
  readonly d = signal<ServiceDetails | null>(null);
  readonly revealed = signal<Record<string, string>>({});
  readonly envFilter = signal('');
  readonly shellOpen = signal(false);
  /** Restart on save for this service: on/off and what is watched (from devdesk.yaml's watch:, or switched here). */
  readonly watch = signal<{ on: boolean; folders: string[] } | null>(null);
  private readonly closed = signal<Set<Section>>(new Set());
  private timer?: ReturnType<typeof setInterval>;
  readonly time = time;

  constructor() {
    effect(() => {
      const name = this.service();
      this.revealed.set({}); // a revealed value never carries over to another service
      this.envFilter.set('');
      this.d.set(null);
      this.watch.set(null);
      this.load(name);
      this.loadWatch(name);
      clearInterval(this.timer);
      this.timer = setInterval(() => this.load(name), 2000);
    });
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  open(s: Section): boolean {
    return !this.closed().has(s);
  }

  toggle(s: Section): void {
    const next = new Set(this.closed());
    next.has(s) ? next.delete(s) : next.add(s);
    this.closed.set(next);
  }

  running(d: ServiceDetails): boolean {
    return ['running', 'healthy', 'unhealthy', 'starting', 'waiting', 'stopping'].includes(d.service.state.label);
  }

  private async loadWatch(name: string): Promise<void> {
    const r = await this.engine.watchStatus();
    if (r.ok && name === this.service()) this.watch.set(r.value[name] ?? null);
  }

  async toggleWatch(on: boolean): Promise<void> {
    this.error.set(null);
    const r = await this.engine.setWatch(this.service(), on);
    if (r.ok) this.watch.set(r.value[this.service()] ?? null);
    else this.error.set(r.error);
  }

  act(action: 'start' | 'stop' | 'restart'): void {
    this.action.emit({ service: this.service(), action });
  }

  /**
   * What devdesk.yaml (and DevDesk) set for this service comes first: that's what you look for. The dozens inherited
   * from your shell are folded away. Grouping only; the engine says where each variable came from.
   */
  envGroups(d: ServiceDetails) {
    const f = this.envFilter().toLowerCase();
    const rank: Record<string, number> = { service: 0, 'env-file': 1, devdesk: 2 };
    const shown = d.environment.filter((v) => !f || v.key.toLowerCase().includes(f));
    return {
      own: shown.filter((v) => v.source !== 'shell').sort((a, b) => rank[a.source] - rank[b.source] || a.key.localeCompare(b.key)),
      shell: shown.filter((v) => v.source === 'shell').sort((a, b) => a.key.localeCompare(b.key)),
    };
  }

  charts(d: ServiceDetails) {
    const line = (values: number[]) => {
      const max = Math.max(...values, 1e-9);
      const step = 100 / (values.length - 1);
      return values.map((v, i) => `${(i * step).toFixed(1)},${(23 - (v / max) * 21).toFixed(1)}`).join(' ');
    };
    const r = d.resources;
    const last = r[r.length - 1];
    return [
      { label: 'CPU', points: line(r.map((x) => x.cpu)), last: cpu(last.cpu) },
      { label: 'memory', points: line(r.map((x) => x.memory)), last: bytes(last.memory) },
      { label: 'sockets', points: line(r.map((x) => x.sockets)), last: String(last.sockets) },
    ];
  }

  async show(service: string, key: string): Promise<void> {
    const value = await this.engine.reveal(service, key);
    if (value !== null) this.revealed.update((r) => ({ ...r, [key]: value }));
  }

  hide(key: string): void {
    this.revealed.update(({ [key]: _, ...rest }) => rest);
  }

  short(path: string): string {
    return path.replace(/^\/home\/[^/]+/, '~');
  }

  private async load(name: string): Promise<void> {
    const d = await this.engine.details(name);
    if (name === this.service()) this.d.set(d);
  }
}
