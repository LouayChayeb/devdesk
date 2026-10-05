import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { Engine, MachineView } from './engine';

/** What the machine shows right now, like `devdesk ps`: who talks to whom, open sockets, and ghosts. Display only. */
@Component({
  selector: 'dd-connections',
  template: `
    <h4>Who talks to whom</h4>
    @if (!machine()?.connections?.length) {
      <div class="none">No open connections from the services right now.</div>
    }
    <table class="mono">
      @for (l of machine()?.connections ?? []; track $index) {
        <tr>
          <td class="from">{{ l.from }}</td>
          <td class="arrow">→</td>
          <td>
            @if (l.to) {
              {{ l.to }} <span class="dim">:{{ port(l.target) }}</span>
            } @else {
              {{ l.target }} <span class="dim">outside the project</span>
            }
          </td>
          <td class="num">{{ l.open }} open</td>
        </tr>
      }
    </table>

    @if (sockets().length) {
      <h4>Open sockets</h4>
      <table class="mono">
        @for (s of sockets(); track s.name) {
          <tr><td class="from">{{ s.name }}</td><td class="num">{{ s.count }}</td></tr>
        }
      </table>
    }

    <h4>Started by hand, not by DevDesk</h4>
    @if (!machine()?.ghosts?.length) {
      <div class="none">No other programs of yours are running in this project's folder.</div>
    }
    @for (g of machine()?.ghosts ?? []; track g.pid) {
      <div class="ghost-card">
        <div class="mono"><b>PID {{ g.pid }}</b> {{ g.command }}</div>
        <div class="meta mono">
          in {{ g.folder || '.' }}
          @if (g.children) { · +{{ g.children }} child processes }
          @if (g.ports.length) { · ports {{ g.ports.join(', ') }} }
        </div>
        @for (b of blocks(g.blocks); track b.service) {
          <div class="blocks">↳ blocks {{ b.service }}, which needs port {{ b.port }}</div>
        }
        <div class="hint">DevDesk never stops these. Stop it in its terminal (Ctrl+C), or: <code>kill {{ g.pid }}</code></div>
      </div>
    }
  `,
  styles: `
    :host { display: block; padding: 12px 16px; max-width: 920px; }
    h4 { font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin: 12px 0 6px; }
    .none { color: var(--text-2); font-size: var(--fs-sm); }
    table { border-collapse: collapse; font-size: var(--fs-sm); }
    td { padding: 2px 12px 2px 0; }
    .from { color: var(--text-1); min-width: 80px; }
    .arrow, .dim { color: var(--text-3); }
    .num { text-align: right; font-variant-numeric: tabular-nums; color: var(--text-2); }
    .ghost-card { background: var(--bg-1); border: 1px solid var(--border); border-left: 3px solid var(--warn); border-radius: var(--radius); padding: 8px 12px; margin-bottom: 8px; font-size: var(--fs-sm); }
    .meta { color: var(--text-2); margin-top: 2px; }
    .blocks { color: var(--warn); margin-top: 2px; }
    .hint { color: var(--text-3); margin-top: 4px; }
    code { font-family: var(--font-mono); color: var(--text-1); }
  `,
})
export class ConnectionsPanel implements OnInit, OnDestroy {
  readonly engine = inject(Engine);
  readonly machine = signal<MachineView | null>(null);
  private timer?: ReturnType<typeof setInterval>;

  sockets(): { name: string; count: number }[] {
    return Object.entries(this.engine.resources()).map(([name, r]) => ({ name, count: r.sockets ?? 0 }));
  }

  blocks(b: Record<string, number>): { service: string; port: number }[] {
    return Object.entries(b).map(([service, port]) => ({ service, port }));
  }

  port(target: string): string {
    return target.slice(target.lastIndexOf(':') + 1);
  }

  ngOnInit(): void {
    this.load();
    this.timer = setInterval(() => this.load(), 3000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private async load(): Promise<void> {
    this.machine.set(await this.engine.machine());
  }
}
