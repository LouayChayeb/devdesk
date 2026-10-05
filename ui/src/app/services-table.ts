import { Component, inject, input, output } from '@angular/core';
import { Engine } from './engine';
import { StatusDot } from './status-dot';
import { bytes, cpu } from './format';

/** DESIGN.md §4.3: one row per service, every value straight from the engine. */
@Component({
  selector: 'dd-services',
  imports: [StatusDot],
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
          <tr [class.sel]="selected() === s.name" (click)="select.emit(s.name)" tabindex="0" (keydown.enter)="select.emit(s.name)">
            <td class="dot-col"><dd-dot [label]="s.state.label" /></td>
            <td class="name">{{ s.name }}</td>
            <td class="state" [title]="s.state.detail || s.state.label">
              <span class="label {{ s.state.label }}">{{ s.state.label }}</span>
              @if (s.state.detail) {
                <span class="detail">{{ s.state.detail }}</span>
              }
            </td>
            <td class="kind" [title]="s.workload">{{ s.kind === 'container' ? '▣ container' : '› process' }}</td>
            <td class="num mono">{{ s.ports.length ? s.ports.join(', ') : '–' }}</td>
            <td class="num mono">{{ active(s.state.label) ? cpu(engine.resources()[s.name]?.cpu) : '–' }}</td>
            <td class="num mono">{{ active(s.state.label) ? bytes(engine.resources()[s.name]?.memory) : '–' }}</td>
            <td class="num mono">{{ s.state.pid ?? '–' }}</td>
            <td class="num mono">{{ s.state.restarts ?? 0 }}</td>
            <td class="act">
              @if ((engine.findings()[s.name]?.length ?? 0) > 0) {
                <button class="why" (click)="why.emit(s.name); $event.stopPropagation()">Why?</button>
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: `
    table { width: 100%; border-collapse: collapse; }
    th { text-align: left; font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.05em; padding: 8px 12px; border-bottom: 1px solid var(--border); position: sticky; top: 0; background: var(--bg-0); }
    td { padding: 7px 12px; border-bottom: 1px solid var(--border); white-space: nowrap; }
    tr { cursor: pointer; transition: background var(--ease); }
    tbody tr:hover { background: var(--bg-2); }
    tr.sel { background: var(--accent-weak); }
    .dot-col { width: 28px; padding-right: 0; }
    .name { font-weight: 500; }
    .state { max-width: 420px; overflow: hidden; text-overflow: ellipsis; }
    .label { margin-right: 8px; }
    .label.healthy, .label.running, .label.completed { color: var(--ok); }
    .label.starting, .label.waiting, .label.blocked, .label.stopping { color: var(--warn); }
    .label.unhealthy, .label.crashed, .label.crash-loop, .label.failed { color: var(--err); }
    .label.stopped { color: var(--text-3); }
    .detail { color: var(--text-2); font-size: var(--fs-sm); }
    .kind { color: var(--text-2); font-size: var(--fs-sm); }
    .num { text-align: right; color: var(--text-2); }
    .act { width: 64px; text-align: right; }
    .why { padding: 2px 8px; font-size: var(--fs-sm); border-color: var(--err); color: var(--err); background: var(--err-bg); }
  `,
})
export class ServicesTable {
  readonly engine = inject(Engine);
  readonly selected = input<string | null>(null);
  readonly select = output<string>();
  readonly why = output<string>();
  readonly cpu = cpu;
  readonly bytes = bytes;

  /** Numbers are only meaningful while something runs; a stopped service shows "–", not its last sample. */
  active(label: string): boolean {
    return ['running', 'healthy', 'unhealthy', 'starting', 'stopping'].includes(label);
  }
}
