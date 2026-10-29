import { Component, OnDestroy, OnInit, computed, inject, input, signal } from '@angular/core';
import { Engine, PortRow } from './engine';

/** Every listening port on the machine and its owner. The engine decides owners and conflicts. */
@Component({
  selector: 'dd-ports',
  template: `
    <div class="bar">
      <input class="mono" placeholder="Filter: port, program, owner…" [value]="filter()" (input)="filter.set($any($event.target).value)" />
      <label><input type="checkbox" [checked]="onlyProject()" (change)="onlyProject.set(!onlyProject())" /> only this project</label>
      <span class="grow"></span>
      <span class="dim">{{ rows().length }} listening</span>
    </div>
    <table class="mono">
      <thead>
        <tr><th class="num">Port</th><th>Proto</th><th>Address</th><th>Process</th><th class="num">PID</th><th class="num">UID</th><th>Owner</th></tr>
      </thead>
      <tbody>
        @for (r of rows(); track $index) {
          <tr [class.conflict]="r.conflict">
            <td class="num">{{ r.port }}</td>
            <td class="dim">tcp</td>
            <td [class.open]="r.address === '0.0.0.0' || r.address === '::'" [title]="r.address === '0.0.0.0' || r.address === '::' ? 'reachable from other machines' : ''">{{ r.address }}</td>
            <td class="cmd" [title]="r.command ?? ''">{{ r.command ?? '(another user: only the uid is visible)' }}</td>
            <td class="num">{{ r.pid ?? '–' }}</td>
            <td class="num dim">{{ r.uid }}</td>
            <td class="owner {{ r.owner }}">
              {{ ownerText(r) }}
              @if (r.conflict && r.fix) { <div class="fix">{{ r.fix }}</div> }
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: `
    :host { display: block; padding: 12px 16px; }
    .bar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; color: var(--text-2); font-size: var(--fs-sm); }
    .bar input[type='text'], .bar input:not([type]) { width: 280px; }
    .bar label { display: flex; gap: 4px; align-items: center; }
    .grow { flex: 1; }
    .dim { color: var(--text-3); }
    table { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
    th { text-align: left; font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.05em; padding: 6px 10px; border-bottom: 1px solid var(--border); }
    td { padding: 5px 10px; border-bottom: 1px solid var(--border); vertical-align: top; }
    .num { text-align: right; font-variant-numeric: tabular-nums; }
    .cmd { max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .open { color: var(--warn); }
    tr.conflict { background: var(--err-bg); }
    tr.conflict td:first-child { color: var(--err); font-weight: 600; }
    .owner.this-project { color: var(--ok); }
    .owner.devdesk { color: var(--accent); }
    .owner.other-devdesk-project { color: var(--info); }
    .owner.not-devdesk, .owner.container { color: var(--text-2); }
    .conflict .owner { color: var(--err); }
    .fix { color: var(--text-2); margin-top: 2px; }
  `,
})
export class PortsPanel implements OnInit, OnDestroy {
  readonly engine = inject(Engine);
  readonly all = signal<PortRow[]>([]);
  readonly initialFilter = input('');
  readonly filter = signal('');
  readonly onlyProject = signal(false);
  private timer?: ReturnType<typeof setInterval>;

  readonly rows = computed(() => {
    const f = this.filter().toLowerCase();
    return this.all().filter(
      (r) =>
        (!this.onlyProject() || r.owner === 'this-project' || r.conflict) &&
        (!f || `${r.port} ${r.command ?? ''} ${this.ownerText(r)}`.toLowerCase().includes(f)),
    );
  });

  ownerText(r: PortRow): string {
    switch (r.owner) {
      case 'this-project':
        return `this project · ${r.who}`;
      case 'devdesk':
        return 'DevDesk engine (its local API)';
      case 'other-devdesk-project':
        return `other DevDesk project · ${r.who}`;
      case 'container':
        return r.conflict ? 'a container, not this project — blocks a declared port' : 'a container (docker-proxy)';
      default:
        return r.conflict ? 'not DevDesk — blocks a declared port' : 'not DevDesk';
    }
  }

  ngOnInit(): void {
    this.filter.set(this.initialFilter());
    this.load();
    this.timer = setInterval(() => this.load(), 3000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private async load(): Promise<void> {
    this.all.set((await this.engine.ports()) ?? []);
  }
}
