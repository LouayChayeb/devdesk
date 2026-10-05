import { Component, HostListener, computed, inject, signal } from '@angular/core';
import { ConfigProblem, Engine } from './engine';
import { StatusDot } from './status-dot';
import { ServicesTable } from './services-table';
import { LogsPanel } from './logs-panel';
import { DiagnosisPanel } from './diagnosis-panel';
import { TimelinePanel } from './timeline-panel';
import { ConnectionsPanel } from './connections-panel';
import { bytes, cpu } from './format';

type Tab = 'services' | 'diagnosis' | 'timeline' | 'connections';

const RECENT_KEY = 'devdesk.recent';

@Component({
  selector: 'app-root',
  imports: [StatusDot, ServicesTable, LogsPanel, DiagnosisPanel, TimelinePanel, ConnectionsPanel],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  readonly engine = inject(Engine);
  readonly tab = signal<Tab>('services');
  readonly selected = signal<string | null>(null);
  readonly diagnosisFor = signal<string | null>(null);
  readonly trustOpen = signal(false);
  readonly pathInput = signal('');
  readonly openError = signal<{ error: string; problems?: ConfigProblem[] } | null>(null);
  readonly actionError = signal<string | null>(null);
  readonly busy = signal(false);
  readonly recent = signal<string[]>(this.loadRecent());
  readonly cpu = cpu;
  readonly bytes = bytes;

  readonly services = computed(() => this.engine.project()?.services ?? []);
  /** Recently opened folders, plus the open project even if it was opened elsewhere (CLI, another window). */
  readonly projects = computed(() => {
    const root = this.engine.project()?.root;
    return root && !this.recent().includes(root) ? [root, ...this.recent()] : this.recent();
  });
  readonly healthyCount = computed(
    () => this.services().filter((s) => ['healthy', 'running', 'completed'].includes(s.state.label)).length,
  );
  readonly summaryLabel = computed(() => {
    const all = this.services();
    if (all.some((s) => ['crashed', 'crash-loop', 'failed', 'unhealthy', 'blocked'].includes(s.state.label))) return 'unhealthy';
    if (all.some((s) => ['starting', 'waiting', 'stopping'].includes(s.state.label))) return 'starting';
    if (all.length > 0 && all.every((s) => s.state.label === 'stopped')) return 'stopped';
    return 'healthy';
  });
  readonly findingCount = computed(() => this.engine.allFindings().length + (this.engine.preflight()?.length ?? 0));
  readonly totals = computed(() => {
    const r = Object.values(this.engine.resources());
    return {
      cpu: r.reduce((sum, x) => sum + (x.cpu ?? 0), 0),
      memory: r.reduce((sum, x) => sum + (x.memory ?? 0), 0),
    };
  });
  readonly ports = computed(() => this.services().flatMap((s) => s.ports).length);

  constructor() {
    this.engine.start();
  }

  async pickFolder(): Promise<void> {
    if ('__TAURI_INTERNALS__' in window) {
      const { open } = await import('@tauri-apps/plugin-dialog');
      const folder = await open({ directory: true, title: 'Choose a folder with devdesk.yaml' });
      if (typeof folder === 'string') await this.open(folder);
    }
  }

  async open(path: string): Promise<void> {
    if (!path.trim()) return;
    this.openError.set(null);
    const r = await this.engine.open(path.trim());
    if (!r.ok) {
      this.openError.set({ error: r.error, problems: r.problems });
      return;
    }
    this.selected.set(null);
    this.tab.set('services');
    this.remember(this.engine.project()?.root ?? path.trim());
    this.pathInput.set('');
  }

  async start(): Promise<void> {
    const p = this.engine.project();
    if (!p) return;
    if (!p.trusted) {
      this.trustOpen.set(true); // nothing runs before the user has seen exactly what will run
      return;
    }
    this.busy.set(true);
    this.actionError.set(await this.engine.up());
    this.busy.set(false);
  }

  async trustAndRun(): Promise<void> {
    await this.engine.trust();
    this.trustOpen.set(false);
    await this.start();
  }

  async stop(): Promise<void> {
    this.actionError.set(null);
    await this.engine.down();
  }

  async check(): Promise<void> {
    await this.engine.why();
    this.diagnosisFor.set(null);
    this.tab.set('diagnosis');
  }

  why(service: string): void {
    this.diagnosisFor.set(service);
    this.tab.set('diagnosis');
  }

  @HostListener('document:keydown', ['$event'])
  keys(e: KeyboardEvent): void {
    if (e.key === 'Escape') this.trustOpen.set(false);
    if (e.ctrlKey && e.key === 'Enter') this.engine.running() ? this.stop() : this.start();
  }

  private remember(root: string): void {
    const next = [root, ...this.recent().filter((r) => r !== root)].slice(0, 8);
    this.recent.set(next);
    try {
      localStorage.setItem(RECENT_KEY, JSON.stringify(next));
    } catch {
      /* private mode: recent list just isn't kept */
    }
  }

  private loadRecent(): string[] {
    try {
      return JSON.parse(localStorage.getItem(RECENT_KEY) ?? '[]');
    } catch {
      return [];
    }
  }

  short(path: string): string {
    return path.replace(/^\/home\/[^/]+/, '~');
  }

  base(path: string): string {
    return path.split('/').filter(Boolean).pop() ?? path;
  }
}
