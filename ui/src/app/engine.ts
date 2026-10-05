import { Injectable, computed, signal } from '@angular/core';

/** Shapes sent by the engine (daemon/ApiJson.java). The UI only displays them: no business logic here. */
export interface ServiceStateView {
  label: string;
  detail: string;
  pid?: number;
  restarts?: number;
  cause?: string;
}
export interface ServiceView {
  name: string;
  kind: 'process' | 'container';
  workload: string;
  directory: string;
  ports: number[];
  dependsOn: { service: string; condition: string }[];
  health: string;
  restart: string;
  state: ServiceStateView;
}
export interface ProjectView {
  name: string;
  root: string;
  file: string;
  trusted: boolean;
  commands: string[];
  services: ServiceView[];
}
export interface Finding {
  rule: string;
  severity: 'error' | 'warning';
  service: string;
  title: string;
  explanation: string;
  evidence: string[];
  actions: string[];
}
export interface LogLine {
  service: string;
  stream: 'stdout' | 'stderr';
  text: string;
  at: string;
}
export interface Resources {
  cpu: number | null;
  memory: number | null;
  processes: number;
  sockets: number;
}
export interface HistoryItem {
  at: string;
  service: string;
  kind: 'session' | 'state' | 'finding' | 'warning' | 'resources';
  summary: string;
  data: Record<string, any>;
  routine: boolean;
  afterUncleanStop: boolean;
}
export interface History {
  folder: string;
  items: HistoryItem[];
}
export interface Link {
  from: string;
  to: string | null;
  target: string;
  open: number;
}
export interface GhostView {
  pid: number;
  command: string;
  folder: string | null;
  startedAt: string | null;
  ports: number[];
  children: number;
  blocks: Record<string, number>;
}
export interface MachineView {
  connections: Link[];
  ghosts: GhostView[];
}
export interface ConfigProblem {
  line: number;
  column: number;
  path: string;
  message: string;
}

type Connection = { port: number; token: string };

const MAX_LINES = 20_000;

/** One store for everything the engine reports, fed by its event stream (DESIGN.md §6). */
@Injectable({ providedIn: 'root' })
export class Engine {
  readonly status = signal<'connecting' | 'connected' | 'disconnected'>('connecting');
  readonly error = signal<string | null>(null);
  readonly project = signal<ProjectView | null>(null);
  readonly lines = signal<LogLine[]>([]);
  readonly findings = signal<Record<string, Finding[]>>({});
  readonly preflight = signal<Finding[] | null>(null);
  readonly resources = signal<Record<string, Resources>>({});
  readonly warnings = signal<string[]>([]);

  readonly allFindings = computed(() => Object.values(this.findings()).flat());
  readonly running = computed(() =>
    (this.project()?.services ?? []).some((s) =>
      ['starting', 'running', 'healthy', 'unhealthy', 'stopping', 'waiting'].includes(s.state.label),
    ),
  );

  private connection: Connection | null = null;
  private socket: WebSocket | null = null;
  private retryMs = 500;

  async start(): Promise<void> {
    try {
      this.connection = await this.locate();
      this.listen();
    } catch (e) {
      this.status.set('disconnected');
      this.error.set(String(e));
    }
  }

  /** In the desktop app the Rust shell starts the engine; in a browser (UI development) use ?port=&token=. */
  private async locate(): Promise<Connection> {
    if ('__TAURI_INTERNALS__' in window) {
      const { invoke } = await import('@tauri-apps/api/core');
      return invoke<Connection>('engine_connection');
    }
    const q = new URLSearchParams(location.search);
    const port = Number(q.get('port'));
    const token = q.get('token');
    if (!port || !token) {
      throw new Error('Open the UI with ?port=...&token=... from devdeskd, or run it inside the desktop app.');
    }
    return { port, token };
  }

  private listen(): void {
    const c = this.connection!;
    this.status.set('connecting');
    const ws = new WebSocket(`ws://127.0.0.1:${c.port}/api/v1/events?token=${c.token}`);
    this.socket = ws;
    ws.onopen = () => {
      this.status.set('connected');
      this.error.set(null);
      this.retryMs = 500;
    };
    ws.onmessage = (m) => this.apply(JSON.parse(m.data));
    ws.onclose = () => {
      this.status.set('disconnected');
      // Reconnect with growing waits; the engine sends a full snapshot on every new connection.
      setTimeout(() => this.listen(), this.retryMs);
      this.retryMs = Math.min(this.retryMs * 2, 5000);
    };
  }

  private apply(e: any): void {
    switch (e.type) {
      case 'snapshot': {
        this.project.set(e.project);
        const lines: LogLine[] = Object.values(e.output as Record<string, LogLine[]>).flat();
        lines.sort((a, b) => a.at.localeCompare(b.at));
        this.lines.set(lines.slice(-MAX_LINES));
        this.findings.set(e.findings ?? {});
        break;
      }
      case 'project':
        this.project.set(e.project);
        this.lines.set([]);
        this.findings.set({});
        this.resources.set({});
        this.preflight.set(null);
        break;
      case 'state':
        this.project.update((p) =>
          p && {
            ...p,
            services: p.services.map((s) => (s.name === e.service ? { ...s, state: e.state } : s)),
          },
        );
        if (e.state.label === 'starting') {
          this.findings.update(({ [e.service]: _, ...rest }) => rest);
        }
        break;
      case 'output':
        this.lines.update((l) => {
          const next = l.length >= MAX_LINES ? l.slice(l.length - MAX_LINES + 1) : l.slice();
          next.push(e as LogLine);
          return next;
        });
        break;
      case 'diagnosis':
        this.findings.update((f) => ({ ...f, [e.service]: e.findings }));
        break;
      case 'resources':
        this.resources.set(e.services);
        break;
      case 'warning':
        this.warnings.update((w) => [...w.slice(-20), `${e.service}: ${e.message}`]);
        break;
    }
  }

  // ---- commands: each one just asks the engine, which reports the result as events ----

  async open(path: string): Promise<{ ok: true } | { ok: false; error: string; problems?: ConfigProblem[] }> {
    const r = await this.call('POST', '/project/open', { path });
    return r.ok ? { ok: true } : { ok: false, error: r.body.error, problems: r.body.problems };
  }
  async trust(): Promise<void> {
    const r = await this.call('POST', '/project/trust');
    if (r.ok) this.project.set(r.body);
  }
  async up(): Promise<string | null> {
    const r = await this.call('POST', '/project/up');
    return r.ok ? null : r.body.error;
  }
  async down(): Promise<void> {
    await this.call('POST', '/project/down');
  }
  async why(): Promise<void> {
    const r = await this.call('GET', '/project/why');
    if (r.ok) this.preflight.set(r.body.findings);
  }

  async history(hours: number): Promise<History | null> {
    const r = await this.call('GET', `/project/history?hours=${hours}`);
    return r.ok ? r.body : null;
  }
  async machine(): Promise<MachineView | null> {
    const r = await this.call('GET', '/project/machine');
    return r.ok ? r.body : null;
  }

  private async call(method: string, path: string, body?: unknown): Promise<{ ok: boolean; body: any }> {
    const c = this.connection;
    if (!c) return { ok: false, body: { error: 'not connected to the engine' } };
    const res = await fetch(`http://127.0.0.1:${c.port}/api/v1${path}`, {
      method,
      headers: { Authorization: `Bearer ${c.token}`, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { ok: res.ok, body: await res.json() };
  }
}
