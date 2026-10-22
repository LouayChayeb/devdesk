import { Injectable, computed, effect, signal, untracked } from '@angular/core';

/** The pages of the side menu. Keys 1–9 then 0 follow this order. */
export type Page = 'services' | 'graph' | 'ports' | 'tests' | 'diagnosis' | 'timeline' | 'connections' | 'data' | 'api' | 'mail';

export const NAV: { group: string; pages: { id: Page; label: string; icon: string }[] }[] = [
  {
    group: 'Run',
    pages: [
      { id: 'services', label: 'Services', icon: 'services' },
      { id: 'graph', label: 'Graph', icon: 'graph' },
      { id: 'ports', label: 'Ports', icon: 'ports' },
      { id: 'tests', label: 'Tests', icon: 'check' },
    ],
  },
  {
    group: 'Fix',
    pages: [
      { id: 'diagnosis', label: 'Diagnosis', icon: 'diagnosis' },
      { id: 'timeline', label: 'Timeline', icon: 'timeline' },
      { id: 'connections', label: 'Connections', icon: 'connections' },
    ],
  },
  {
    group: 'Work',
    pages: [
      { id: 'data', label: 'Data', icon: 'database' },
      { id: 'api', label: 'API', icon: 'send' },
      { id: 'mail', label: 'Inbox', icon: 'mail' },
    ],
  },
];
export const PAGES = NAV.flatMap((g) => g.pages.map((p) => ({ ...p, group: g.group })));

/** The Redis views besides the key browser. */
export type RedisTool = 'memory' | 'slowlog' | 'monitor' | 'pubsub' | 'stats';

/** Something the user opened in the Workbench: it becomes a tab, like a file in an editor. */
export type DocSpec =
  | { kind: 'table'; service: string; schema: string; table: string; filter?: { column: string; value: string } }
  | { kind: 'sql'; service: string; saved?: { id: string; name: string; sql: string } }
  | { kind: 'activity'; service: string }
  | { kind: 'backups'; service: string }
  | { kind: 'redis'; service: string; pattern: string }
  | { kind: 'redis-tools'; service: string; tool: RedisTool }
  | { kind: 'request'; requestId: string | null }
  | { kind: 'run'; collection: string }
  | { kind: 'mail'; service: string }
  /** A message broker's queues (RabbitMQ) or topics (Kafka). */
  | { kind: 'queues'; service: string }
  /** The traffic recorder: record calls to a service, replay them. */
  | { kind: 'recorder' }
  /** The stress lab: climbing load on one URL, and which service gives up first. */
  | { kind: 'stress' }
  | { kind: 'diagram'; service: string; schema: string }
  | { kind: 'stats'; service: string }
  /** A bucket of S3-style storage (MinIO, RustFS). */
  | { kind: 'bucket'; service: string; bucket: string }
  /** An Elasticsearch / OpenSearch index. */
  | { kind: 'index'; service: string; index: string }
  /** Postgres's slowest queries; {@code highlight}: the shape of one query to point at (from a request). */
  | { kind: 'slow'; service: string; highlight?: string }
  | { kind: 'design'; service: string; schema: string; table: string }
  | { kind: 'mock'; open?: string }
  | { kind: 'mongo'; service: string; db: string; collection: string };

export interface Doc {
  id: string;
  spec: DocSpec;
  title: string;
  /** Unsaved edits: the tab shows a dot and asks before closing. */
  dirty: ReturnType<typeof signal<boolean>>;
  /** The view's own state (rows, filters, SQL text…), kept here so it survives switching tabs. */
  state: unknown;
  /** What a view saved of its state when the project was last open (a request being written, SQL text). */
  draft?: unknown;
}

/** A view state that can be written down, so its tab comes back with the project. */
export interface Snapshotting {
  snapshot(): unknown;
}

/** How the open tabs of a project are written down (localStorage, per project folder). */
interface SavedTabs {
  docs: { spec: DocSpec; title: string; draft?: unknown }[];
  active: number;
  split: number;
}

/** Which page shows a kind of doc. */
export function pageOf(spec: DocSpec): Page {
  if (spec.kind === 'mail' || spec.kind === 'queues') return 'mail';
  if (spec.kind === 'mock' || spec.kind === 'recorder' || spec.kind === 'stress') return 'api';
  return spec.kind === 'request' || spec.kind === 'run' ? 'api' : 'data';
}

/** Open tabs of the Workbench. UI state only: what's in a table or a key always comes from the engine. */
@Injectable({ providedIn: 'root' })
export class Workbench {
  readonly docs = signal<Doc[]>([]);
  readonly activeId = signal<string | null>(null);
  readonly active = computed(() => this.docs().find((d) => d.id === this.activeId()) ?? null);
  /** The tab shown on the right half (split view), whatever page is open; null = no split. */
  readonly splitId = signal<string | null>(null);
  readonly split = computed(() => this.docs().find((d) => d.id === this.splitId()) ?? null);
  /** Tab ids, most recently shown first: closing or splitting a tab falls back to the one used before it. */
  private recent: string[] = [];
  private next = 0;
  private sqlCount = 0;
  private requestCount = 0;

  constructor() {
    effect(() => {
      const id = this.activeId();
      if (id) this.recent = [id, ...this.recent.filter((x) => x !== id)];
    });
  }

  /** Opens a tab, or focuses the one already showing the same thing. */
  open(spec: DocSpec): Doc {
    const same = this.docs().find((d) => sameThing(d.spec, spec));
    if (same) {
      if (spec.kind === 'redis-tools' && same.spec.kind === 'redis-tools' && same.spec.tool !== spec.tool) {
        // One Insights tab per Redis: the same tab (and its running streams) switches to the asked view.
        const switched: Doc = { ...same, spec };
        this.docs.update((l) => l.map((d) => (d === same ? switched : d)));
        this.activeId.set(switched.id);
        return switched;
      }
      if (spec.kind === 'table' && spec.filter && !same.dirty()) {
        // Following a link again: the tab starts over with the new filter (a new object, so its view reloads).
        const fresh: Doc = { ...same, spec, state: undefined };
        this.docs.update((l) => l.map((d) => (d === same ? fresh : d)));
        this.activeId.set(fresh.id);
        return fresh;
      }
      this.activeId.set(same.id);
      return same;
    }
    const doc: Doc = { id: `d${++this.next}`, spec, title: this.title(spec), dirty: signal(false), state: undefined };
    this.docs.update((l) => [...l, doc]);
    this.activeId.set(doc.id);
    return doc;
  }

  /** Returns false when the user kept the tab because of unsaved changes. */
  close(id: string): boolean {
    const doc = this.docs().find((d) => d.id === id);
    if (!doc) return true;
    if (doc.dirty() && !confirm(`"${doc.title}" has unsaved changes. Close it and throw them away?`)) return false;
    const list = this.docs();
    const i = list.indexOf(doc);
    const rest = list.filter((d) => d !== doc);
    this.docs.set(rest);
    this.recent = this.recent.filter((x) => x !== id);
    if (this.splitId() === id) this.splitId.set(null);
    if (this.activeId() === id) this.activeId.set(this.recent.find((x) => rest.some((d) => d.id === x)) ?? (rest[i] ?? rest[i - 1])?.id ?? null);
    return true;
  }

  /**
   * Split view: the active tab moves to the right half and the left shows the tab used before it. Again: back to one
   * pane. Returns false when there is only one tab (nothing to put beside it).
   */
  toggleSplit(): boolean {
    if (this.splitId()) {
      this.splitId.set(null);
      return true;
    }
    const a = this.activeId();
    const other = this.recent.find((x) => x !== a && this.docs().some((d) => d.id === x));
    if (!a || !other) return false;
    this.splitId.set(a);
    this.activeId.set(other);
    return true;
  }

  /** A new project: its tables and requests aren't the old project's. */
  clear(): void {
    this.docs.set([]);
    this.activeId.set(null);
    this.splitId.set(null);
    this.recent = [];
  }

  /**
   * A tab moves to its own window: what it shows (and its unsaved draft) is written down for the new window to read,
   * and the tab closes here. Returns the key the window is opened with.
   */
  handOver(doc: Doc): string {
    const key = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
    try {
      localStorage.setItem(POPOUT_KEY + key, JSON.stringify({ spec: doc.spec, title: doc.title, draft: snapshotOf(doc) }));
    } catch {
      // no storage: the window opens the same thing, without the unsaved draft
    }
    return key;
  }

  /** In a popped-out window: the tab it was opened for, read once. */
  takeOver(key: string): Doc | null {
    let saved: { spec: DocSpec; title: string; draft?: unknown } | null = null;
    try {
      saved = JSON.parse(localStorage.getItem(POPOUT_KEY + key) ?? 'null');
      localStorage.removeItem(POPOUT_KEY + key);
    } catch {
      saved = null;
    }
    if (!saved) return null;
    const doc: Doc = { id: `d${++this.next}`, spec: saved.spec, title: saved.title, dirty: signal(false), state: undefined, draft: saved.draft };
    this.docs.set([doc]);
    this.activeId.set(doc.id);
    return doc;
  }

  /** Writes down the open tabs (and drafts) of the project at {@code root}. */
  save(root: string): void {
    const docs = this.docs();
    const saved: SavedTabs = {
      docs: docs.map((d) => ({ spec: d.spec, title: d.title, draft: snapshotOf(d) })),
      active: docs.findIndex((d) => d.id === this.activeId()),
      split: docs.findIndex((d) => d.id === this.splitId()),
    };
    try {
      localStorage.setItem(TABS_KEY + root, JSON.stringify(saved));
    } catch {
      // storage full or blocked: the tabs just won't come back
    }
  }

  /** Reopens the tabs the project had last time. Their content is read again from the engine. */
  restore(root: string): void {
    let saved: SavedTabs | null = null;
    try {
      saved = JSON.parse(localStorage.getItem(TABS_KEY + root) ?? 'null');
    } catch {
      saved = null;
    }
    if (!saved?.docs?.length) return;
    const docs: Doc[] = saved.docs.map((d) => ({ id: `d${++this.next}`, spec: d.spec, title: d.title, dirty: signal(false), state: undefined, draft: d.draft }));
    untracked(() => {
      this.docs.set(docs);
      this.recent = [];
      this.activeId.set(docs[saved.active]?.id ?? docs[0].id);
      this.splitId.set(docs[saved.split]?.id ?? null);
    });
  }

  rename(id: string, title: string): void {
    const doc = this.docs().find((d) => d.id === id);
    if (doc) {
      doc.title = title;
      this.docs.update((l) => [...l]);
    }
  }

  /**
   * The most recent tab shown on a page, so returning to Data shows what was open there. The split tab has its own
   * half, so it isn't one of them.
   */
  lastOn(page: Page): Doc | null {
    const docs = this.docs().filter((d) => d.id !== this.splitId() && pageOf(d.spec) === page);
    const a = this.active();
    if (a && docs.includes(a)) return a;
    for (const id of this.recent) {
      const d = docs.find((x) => x.id === id);
      if (d) return d;
    }
    return docs[docs.length - 1] ?? null;
  }

  private title(spec: DocSpec): string {
    switch (spec.kind) {
      case 'table':
        return spec.schema === 'public' || spec.schema === 'main' ? spec.table : `${spec.schema}.${spec.table}`;
      case 'sql':
        return spec.saved ? spec.saved.name : `SQL ${++this.sqlCount} · ${spec.service}`;
      case 'activity':
        return `Sessions · ${spec.service}`;
      case 'redis-tools':
        return `Insights · ${spec.service}`;
      case 'backups':
        return `Backups · ${spec.service}`;
      case 'redis':
        return `${spec.pattern} · ${spec.service}`;
      case 'request':
        return spec.requestId ? 'Request' : `New request ${++this.requestCount}`;
      case 'run':
        return `Run · ${spec.collection || 'No collection'}`;
      case 'mail':
        return `Mail · ${spec.service}`;
      case 'queues':
        return `Queues · ${spec.service}`;
      case 'diagram':
        return `Diagram · ${spec.service}`;
      case 'stats':
        return `Size and users · ${spec.service}`;
      case 'slow':
        return `Slowest queries · ${spec.service}`;
      case 'bucket':
        return spec.bucket;
      case 'index':
        return spec.index;
      case 'mock':
        return 'Mock server';
      case 'recorder':
        return 'Traffic recorder';
      case 'stress':
        return 'Stress lab';
      case 'mongo':
        return spec.collection;
      case 'design':
        return spec.table ? `Change ${spec.table}` : `New table · ${spec.service}`;
    }
  }
}

const TABS_KEY = 'devdesk.tabs:';
const POPOUT_KEY = 'devdesk.popout:';

function snapshotOf(d: Doc): unknown {
  const st = d.state as Partial<Snapshotting> | undefined;
  return st && typeof st.snapshot === 'function' ? st.snapshot() : d.draft;
}

function sameThing(a: DocSpec, b: DocSpec): boolean {
  if (a.kind === 'table' && b.kind === 'table') return a.service === b.service && a.schema === b.schema && a.table === b.table;
  if (a.kind === 'redis' && b.kind === 'redis') return a.service === b.service && a.pattern === b.pattern;
  if (a.kind === 'request' && b.kind === 'request') return a.requestId !== null && a.requestId === b.requestId;
  if (a.kind === 'run' && b.kind === 'run') return a.collection === b.collection;
  if (a.kind === 'sql' && b.kind === 'sql') return !!a.saved && a.saved.id === b.saved?.id;
  if ((a.kind === 'activity' && b.kind === 'activity') || (a.kind === 'backups' && b.kind === 'backups') || (a.kind === 'stats' && b.kind === 'stats')) return a.service === b.service;
  if (a.kind === 'bucket' && b.kind === 'bucket') return a.service === b.service && a.bucket === b.bucket;
  if (a.kind === 'index' && b.kind === 'index') return a.service === b.service && a.index === b.index;
  if (a.kind === 'slow' && b.kind === 'slow') return a.service === b.service && a.highlight === b.highlight;
  if (a.kind === 'redis-tools' && b.kind === 'redis-tools') return a.service === b.service;
  if (a.kind === 'mail' && b.kind === 'mail') return a.service === b.service;
  if (a.kind === 'queues' && b.kind === 'queues') return a.service === b.service;
  if (a.kind === 'mock' && b.kind === 'mock') return true;
  if (a.kind === 'recorder' && b.kind === 'recorder') return true;
  if (a.kind === 'stress' && b.kind === 'stress') return true;
  if (a.kind === 'mongo' && b.kind === 'mongo') return a.service === b.service && a.db === b.db && a.collection === b.collection;
  if (a.kind === 'design' && b.kind === 'design') return !!a.table && a.service === b.service && a.schema === b.schema && a.table === b.table;
  if (a.kind === 'diagram' && b.kind === 'diagram') return a.service === b.service && a.schema === b.schema;
  return false; // every new SQL tab is its own scratch editor
}
