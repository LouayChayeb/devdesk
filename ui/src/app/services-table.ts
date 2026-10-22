import { Component, inject, input, output } from '@angular/core';
import { Engine } from './engine';
import { StatusDot } from './status-dot';
import { Icon } from './icon';
import { bytes, cpu } from './format';

/** DESIGN.md §4.3: one row per service, every value straight from the engine. */
@Component({
  selector: 'dd-services',
  imports: [StatusDot, Icon],
  template: `
    <table>
      <thead>
        <tr>
          <th class="dot-col"></th>
          <th>Name</th>
          <th>State</th>
          <th>Type</th>
          <th class="num">Ports</th>
          <th class="num">CPU</th>
          <th class="num">Mem</th>
          <th class="num">PID</th>
          <th class="num">Restarts</th>
          <th></th>
        </tr>
      </thead>
      <tbody>
        @for (s of engine.project()?.services ?? []; track s.name) {
          <tr
            [class.sel]="selected() === s.name"
            (click)="select.emit(s.name)"
            (dblclick)="details.emit(s.name)"
            tabindex="0"
            (keydown.enter)="details.emit(s.name)"
          >
            <td class="dot-col"><dd-dot [label]="s.state.label" /></td>
            <td class="name">{{ s.name }}</td>
            <td class="state" [title]="s.state.detail || s.state.label">
              @if (asleep(s.name) && s.state.label === 'stopped') {
                <span class="pill stopped" title="Auto-sleep: nobody used it for a while. A connection to its port wakes it.">asleep</span>
              } @else {
                <span class="pill {{ s.state.label }}">{{ s.state.label }}</span>
              }
              @if (s.state.detail) {
                <span class="detail">{{ s.state.detail }}</span>
              }
            </td>
            <td class="kind" [title]="s.workload"><dd-icon [name]="s.kind === 'container' ? 'box' : 'terminal'" [size]="13" /> {{ s.kind }}</td>
            <td class="num mono">{{ s.ports.length ? s.ports.join(', ') : '–' }}</td>
            <td class="num mono">{{ active(s.state.label) ? cpu(engine.resources()[s.name]?.cpu) : '–' }}</td>
            <td class="num mono">{{ active(s.state.label) ? bytes(engine.resources()[s.name]?.memory) : '–' }}</td>
            <td class="num mono">{{ s.state.pid ?? '–' }}</td>
            <td class="num mono">{{ s.state.restarts ?? 0 }}</td>
            <td class="act">
              <span class="hover-actions">
                @if (active(s.state.label) || s.state.label === 'waiting') {
                  <button class="ghost icon sm" title="Restart {{ s.name }} (and what needs it)" (click)="act(s.name, 'restart', $event)"><dd-icon name="restart" [size]="14" /></button>
                  <button class="ghost icon sm stop" title="Stop {{ s.name }} (and what needs it)" (click)="act(s.name, 'stop', $event)"><dd-icon name="stop" [size]="11" /></button>
                } @else {
                  <button class="ghost icon sm go" title="Start {{ s.name }} (and what it needs)" (click)="act(s.name, 'start', $event)"><dd-icon name="play" [size]="12" /></button>
                }
                <button class="ghost icon sm" title="Details (Enter)" (click)="details.emit(s.name); $event.stopPropagation()"><dd-icon name="more" [size]="16" /></button>
              </span>
              @if ((engine.findings()[s.name]?.length ?? 0) > 0) {
                <button class="danger sm" (click)="why.emit(s.name); $event.stopPropagation()">Why?</button>
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: `
    :host { display: block; padding: 8px 12px; }
    table { width: 100%; border-collapse: separate; border-spacing: 0; }
    th { text-align: left; font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.05em; padding: 8px 12px; border-bottom: 1px solid var(--border); position: sticky; top: 0; background: var(--bg-0); z-index: 1; }
    td { padding: 0 12px; height: 40px; border-bottom: 1px solid var(--border); white-space: nowrap; transition: background var(--ease); }
    tr { cursor: pointer; }
    tbody tr:hover td { background: var(--bg-1); }
    tbody tr.sel td { background: var(--accent-weak); }
    tbody tr.sel td:first-child { box-shadow: inset 2px 0 0 var(--accent); }
    tbody tr:focus-visible { outline: none; }
    tbody tr:focus-visible td { background: var(--bg-2); }
    .dot-col { width: 28px; padding-right: 0; }
    .name { font-weight: 600; }
    .state { max-width: 420px; overflow: hidden; text-overflow: ellipsis; }
    .pill { display: inline-flex; align-items: center; height: 20px; padding: 0 8px; border-radius: 999px; font-size: var(--fs-sm); margin-right: 8px; background: var(--bg-2); color: var(--text-2); }
    .pill.healthy, .pill.running, .pill.completed { color: var(--ok); background: var(--ok-bg); }
    .pill.starting, .pill.waiting, .pill.blocked, .pill.stopping { color: var(--warn); background: var(--warn-bg); }
    .pill.unhealthy, .pill.crashed, .pill.crash-loop, .pill.failed { color: var(--err); background: var(--err-bg); }
    .pill.stopped { color: var(--text-3); }
    .detail { color: var(--text-2); font-size: var(--fs-sm); }
    .kind { color: var(--text-2); font-size: var(--fs-sm); }
    .kind dd-icon { vertical-align: -2px; margin-right: 2px; color: var(--text-3); }
    .num { text-align: right; color: var(--text-2); }
    .act { width: 180px; text-align: right; }
    .hover-actions { display: inline-flex; gap: 2px; margin-right: 6px; opacity: 0; transform: translateX(4px); transition: opacity var(--ease), transform var(--ease); vertical-align: middle; }
    tr:hover .hover-actions, tr:focus-within .hover-actions, tr.sel .hover-actions { opacity: 1; transform: none; }
    .hover-actions button { color: var(--text-2); }
    .go:hover:not(:disabled) { color: var(--ok); }
    .stop:hover:not(:disabled) { color: var(--err); }
  `,
})
export class ServicesTable {
  /** Auto-sleep stopped it to free memory; the engine says which ones. */
  asleep(name: string): boolean {
    return this.engine.project()?.sleeping?.includes(name) ?? false;
  }

  readonly engine = inject(Engine);
  readonly selected = input<string | null>(null);
  readonly select = output<string>();
  readonly why = output<string>();
  readonly details = output<string>();
  readonly action = output<{ service: string; action: 'start' | 'stop' | 'restart' }>();

  act(service: string, action: 'start' | 'stop' | 'restart', e: Event): void {
    e.stopPropagation();
    this.action.emit({ service, action });
  }
  readonly cpu = cpu;
  readonly bytes = bytes;

  /** Numbers are only meaningful while something runs; a stopped service shows "–", not its last sample. */
  active(label: string): boolean {
    return ['running', 'healthy', 'unhealthy', 'starting', 'stopping'].includes(label);
  }
}
