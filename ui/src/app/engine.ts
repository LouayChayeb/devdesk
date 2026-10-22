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
  /** Workbench views the engine offers for this service, e.g. ["data"] for a database. */
  tools?: string[];
  state: ServiceStateView;
}
export interface ProjectView {
  name: string;
  root: string;
  file: string;
  trusted: boolean;
  commands: string[];
  services: ServiceView[];
  /** Profiles devdesk.yaml offers ("demo", "test"), and the one this machine uses ("" = default). */
  profiles?: string[];
  profile?: string;
  /** The git branch the project folder is on, and the services whose data is kept per branch. */
  branch?: string | null;
  perBranch?: string[];
  /** Services auto-sleep stopped; a connection to their port wakes them. */
  sleeping?: string[];
  /** Start levels from the engine's dependency graph: level 0 needs nothing. */
  levels?: string[][];
  /** Per service: everything it needs, and everything that breaks if it stops. */
  reach?: Record<string, { needs: string[]; impact: string[] }>;
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
  /** An error printed while an API-client request ran against this service: the request most likely behind it. */
  request?: { id: number; method: string; url: string };
}
/** A table form: "was" is a part's current name (absent for a new column, index or link). */
export interface DesignView {
  schema: string;
  name: string;
  columns: { was?: string; name: string; type: string; nullable: boolean; default: string | null; primaryKey: boolean }[];
  indexes: { was?: string; name: string; columns: string[]; unique: boolean }[];
  links: { was?: string; name: string; columns: string[]; toSchema: string; toTable: string; toColumns: string[] }[];
}
export interface StatsView {
  databaseBytes: number;
  hints: { table: string; text: string }[];
  tables: { schema: string; name: string; rows: number; totalBytes: number; indexBytes: number; seqScans: number; indexScans: number; deadRows: number; lastVacuum: string | null; lastAnalyze: string | null }[];
  roles: { name: string; attributes: string[]; memberOf: string[]; privileges: string[] }[];
}
export interface DiagramView {
  tables: { id: string; schema: string; name: string; rows: number; columns: { name: string; type: string; nullable: boolean; primaryKey: boolean; link: boolean }[] }[];
  levels: string[][];
  links: { name: string; from: string; columns: string[]; to: string; toColumns: string[] }[];
  outside: string[];
}
export interface MailCatcherView {
  service: string;
  url: string;
  smtpPort: number;
  state: ServiceStateView;
}
export interface MailSummaryView {
  id: string;
  from: string;
  to: string[];
  subject: string;
  at: string;
  size: number;
  read: boolean;
  attachments: number;
  snippet: string;
}
export interface MailPage {
  total: number;
  unread: number;
  mails: MailSummaryView[];
}
export interface MailView extends Omit<MailSummaryView, 'attachments'> {
  cc: string[];
  text: string;
  html: string;
  headers: { name: string; value: string }[];
  attachments: { partId: string; name: string; contentType: string; size: number }[];
  /** request: the URL as the API client writes it ({{api.url}}/confirm?...) when it goes to a service of the project */
  links: { url: string; service: string | null; request: string }[];
}
export interface Resources {
  cpu: number | null;
  memory: number | null;
  processes: number;
  sockets: number;
}
/** A stress run: each second's numbers, each service's state and CPU per second, and the engine's verdict. */
export interface StressView {
  running: boolean;
  plan: { method: string; url: string; maxConcurrency: number; seconds: number } | null;
  seconds: { second: number; concurrency: number; requests: number; errors: number; p50: number; p95: number; max: number; errorKinds: Record<string, number> }[];
  services: Record<string, { second: number; label: string; cpu: number; memory: number }[]>;
  verdict: { text: string; breakingSecond: number | null; firstToGiveUp: string | null } | null;
}
/** The project on another machine over SSH: where, the steps of the last run, and the tunnel to its engine. */
export interface RemoteView {
  target: { user: string; host: string; port: number; folder: string; identityFile?: string } | null;
  mode: string;
  running: boolean;
  connected: boolean;
  syncing: boolean;
  engine: { port: number; token: string } | null;
  forwards: { service: string; port: number }[];
  steps: { name: string; status: string; command: string; output: string[] }[];
}
/** A traffic recording: calls to one service, made through a stand-in port. */
export interface RecordingView {
  id: string;
  service: string;
  targetPort: number;
  proxyPort: number;
  started: string;
  stopped: string | null;
  live: boolean;
  count: number;
}
export interface RecordedCall {
  seq: number;
  at: string;
  caller: string;
  method: string;
  path: string;
  requestHeaders: Record<string, string[]>;
  requestBody: string;
  status: number;
  responseHeaders: Record<string, string[]>;
  responseBody: string;
  ms: number;
}
export interface ReplayResult {
  seq: number;
  method: string;
  path: string;
  was: number;
  now: number;
  ms: number;
  same: boolean;
  differences: string[];
}
/** A message broker of the project and which services are connected to it from this machine. */
export interface BrokerView {
  service: string;
  kind: 'rabbitmq' | 'kafka' | 'sqs';
  port: number;
  clientPort: number;
  state: ServiceStateView;
  connected: Record<string, number>;
}
export interface QueueSummary {
  name: string;
  waiting: number;
  inFlight: number;
  readers: number;
}
export interface QueueMessage {
  position: string;
  at: string | null;
  key: string | null;
  body: string;
  headers: Record<string, string>;
}
export interface QueueReader {
  name: string;
  client: string;
  from: string;
  behind: number;
}
/** S3-style storage: one folder level of a bucket. */
export interface BucketListing {
  folders: string[];
  files: { key: string; size: number; modified: string }[];
  next: string | null;
}
/** The start of a file: text when it reads as text, an image (base64) when it is one. */
export interface FilePreview {
  contentType: string;
  size: number;
  truncated: boolean;
  image: string | null;
  text: string | null;
}
export interface SearchHits {
  total: number;
  exact: boolean;
  tookMs: number;
  hits: { id: string; score: number; source: string }[];
}
/** Postgres's slowest-queries list, or the step that's missing ("load": restart or a devdesk.yaml line). */
export interface SlowQueriesView {
  status: 'ready' | 'not_loaded' | 'not_created';
  since: string | null;
  load?: 'restart' | 'yaml';
  yaml?: string | null;
  statements: SlowStatement[];
}
export interface SlowStatement {
  query: string;
  calls: number;
  totalMs: number;
  meanMs: number;
  maxMs: number;
  rows: number;
  shape: string;
}
/** One folder of migrations compared with its database. The engine writes the words; the app shows them. */
export interface MigrationSource {
  tool: 'flyway' | 'prisma' | 'alembic';
  folder: string;
  database: string | null;
  upToDate: boolean;
  fine?: string;
  title?: string;
  explanation?: string;
  evidence?: string[];
  actions?: string[];
}
/** "Fill with test rows": how each column is filled, a few example rows, and what stops it. */
export interface FakePreview {
  plan: { column: string; how: string; generated: boolean }[];
  examples: Record<string, string | null>[];
  problem: string | null;
}
/** A snapshot of the project's data ("save game"). An item without a file wasn't saved; problem says why. */
export interface SnapshotView {
  id: string;
  label: string;
  created: string;
  complete: boolean;
  bytes: number;
  items: { kind: 'database' | 'redis' | 'settings'; name: string; file: string | null; bytes: number; problem: string | null }[];
}
export interface SnapshotRestore {
  results: { kind: string; name: string; result: string }[];
  ms: number;
  savedFirst?: SnapshotView;
}
/** One startup; times are milliseconds from its beginning, null = never got there. */
export interface Startup {
  began: string;
  tookMs: number | null;
  services: StartupRow[];
}
export interface StartupRow {
  service: string;
  requestedMs: number;
  startingMs: number | null;
  runningMs: number | null;
  readyMs: number | null;
  endedMs: number | null;
  waitedFor: string | null;
  dependsOn: string[];
  outcome: 'ready' | 'failed' | 'starting' | 'stopped';
  slowestChain: boolean;
  detail: string;
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
export interface ServiceDetails {
  service: ServiceView;
  since: string | null;
  pids: number[];
  probes: { ok: boolean; detail: string }[];
  lastError: string | null;
  resources: { at: string; cpu: number; memory: number; sockets: number }[];
  ports: { declared: number[]; listening: number[] | null; notListening: number[]; undeclared: number[] };
  environment: { key: string; source: string; secret: boolean }[];
  dependsOn: string[];
  neededBy: string[];
}
export interface PortRow {
  port: number;
  address: string;
  pid: number | null;
  command: string | null;
  uid: number;
  owner: 'this-project' | 'devdesk' | 'other-devdesk-project' | 'container' | 'not-devdesk';
  who: string | null;
  conflict: boolean;
  fix: string | null;
}
export type DataFeature = 'plan' | 'analyze' | 'activity' | 'backup' | 'export' | 'import' | 'stats' | 'slow';
export interface DatabaseView {
  service: string;
  kind: 'postgres' | 'mysql' | 'sqlite' | 'redis' | 'mongo' | 'objects' | 'search';
  host: string;
  port: number;
  user?: string;
  database?: string;
  state: ServiceStateView;
  /** What the engine can do with this database; the app only offers these. */
  features: DataFeature[];
}
export interface PlanNodeView {
  type: string;
  relation: string | null;
  detail: string[];
  cost: number;
  planRows: number;
  actualRows: number | null;
  removed: number | null;
  ms: number | null;
  spill: boolean;
  children: PlanNodeView[];
}
export interface PlanView {
  root: PlanNodeView;
  planningMs: number | null;
  executionMs: number | null;
  hints: string[];
  raw: string;
}
export interface SessionView {
  pid: number;
  user: string;
  application: string;
  client: string;
  state: string;
  runningMs: number | null;
  waitingFor: string | null;
  query: string;
}
export interface BackupView {
  name: string;
  path: string;
  bytes: number;
  at: string;
  ms?: number;
}
export interface SavedQueryView {
  id: string;
  name: string;
  sql: string;
}
export type FileFormat = 'csv' | 'json' | 'sql';
export interface MemoryView {
  usedBytes: number;
  maxBytes: number;
  policy: string;
  evicted: number;
  totalKeys: number;
  scanned: number;
  biggest: { key: string; type: string; bytes: number; length: number }[];
  prefixes: { prefix: string; keys: number; bytes: number }[];
}
export interface SlowLogView {
  thresholdMicros: number;
  entries: { id: number; at: string; micros: number; words: string[]; client: string }[];
}
export interface WatchEventView {
  seq: number;
  at: string;
  source: string;
  text: string;
}
/** Where an export goes: a file the engine writes, or (in a plain browser) text the page downloads. */
export type ExportAnswer = { rows: number; fileName: string; path?: string; content?: string };
export interface TableView {
  schema: string;
  name: string;
  view: boolean;
  rows: number;
}
export interface StructureView {
  columns: { name: string; type: string; nullable: boolean; default: string | null; primaryKey: boolean }[];
  indexes: { name: string; columns: string[]; unique: boolean }[];
  foreignKeys: { name: string; columns: string[]; toSchema: string; toTable: string; toColumns: string[] }[];
}
export interface ResultView {
  columns: string[];
  types: string[];
  rows: (string | null)[][];
  more: boolean;
  affected: number;
  ms: number;
}
export type FilterOp = 'equals' | 'not_equals' | 'less' | 'greater' | 'contains' | 'is_null' | 'is_not_null';
export interface RowQueryView {
  schema: string;
  table: string;
  orderBy?: string;
  descending?: boolean;
  filters?: { column: string; op: FilterOp; value: string }[];
  offset?: number;
  limit?: number;
}
/** One pending grid edit. Values are text; null means SQL NULL. Rows are named by their whole primary key. */
export type RowChangeView =
  | { op: 'insert'; values: Record<string, string | null> }
  | { op: 'update'; key: Record<string, string>; values: Record<string, string | null> }
  | { op: 'delete'; key: Record<string, string> };
export interface KeyInfoView {
  key: string;
  type: string;
  /** Seconds left; null = never expires. */
  ttl: number | null;
}
export interface KeyValueView extends KeyInfoView {
  length: number;
  text?: string;
  items?: string[];
  fields?: { name: string; value: string }[];
  scored?: { member: string; score: number }[];
  entries?: { id: string; fields: { name: string; value: string }[] }[];
  unsupported?: string;
}
export type RedisEdit =
  | { op: 'set-text' | 'set-json'; value: string }
  | { op: 'hash-set'; field: string; value: string }
  | { op: 'hash-delete'; field: string }
  | { op: 'list-set'; index: number; value: string }
  | { op: 'list-push'; value: string; head: boolean }
  | { op: 'list-remove'; index: number }
  | { op: 'set-add' | 'set-remove' | 'score-remove'; member: string }
  | { op: 'score-set'; member: string; score: number }
  | { op: 'stream-add'; fields: { name: string; value: string }[] }
  | { op: 'group-create'; group: string; fromStart: boolean }
  | { op: 'group-delete'; group: string };
export interface StreamInfoView {
  length: number;
  firstId: string;
  lastId: string;
  hints: string[];
  groups: { name: string; pending: number; lastDelivered: string; lag: number; consumers: { name: string; pending: number; idleMs: number }[] }[];
}
export interface ServerStatsView {
  at: string;
  version: string;
  uptimeSeconds: number;
  opsPerSecond: number;
  clients: number;
  blockedClients: number;
  usedBytes: number;
  keys: number;
  hits: number;
  misses: number;
  expiredKeys: number;
  evictedKeys: number;
  netInBytes: number;
  netOutBytes: number;
}
export interface HeaderView {
  name: string;
  value: string;
}
export type BodyType = 'none' | 'text' | 'form' | 'multipart' | 'graphql';
export interface FormFieldView {
  name: string;
  value: string;
  /** The value is a path on this machine: the engine uploads the file. */
  file: boolean;
}
export type AuthView = { type: 'none' } | { type: 'bearer'; token: string } | { type: 'basic'; user: string; password: string };
export interface HttpCallView {
  method: string;
  url: string;
  headers: HeaderView[];
  bodyType?: BodyType;
  /** The text body, or the GraphQL query. */
  body: string | null;
  /** GraphQL variables (JSON text). */
  variables?: string;
  form?: FormFieldView[];
  auth?: AuthView;
}
export interface MockRoute {
  id: string;
  method: string;
  path: string;
  status: number;
  headers: HeaderView[];
  body: string;
  delayMs: number;
}
export interface MockHit {
  seq: number;
  at: string;
  method: string;
  target: string;
  status: number;
  route: string;
  body: string;
}
export interface MockOverview {
  routes: MockRoute[];
  port: number | null;
  hits: MockHit[];
}
export interface SocketEvent {
  seq: number;
  at: string;
  direction: 'in' | 'out' | 'info';
  text: string;
}
export interface GraphQlOperation {
  kind: 'query' | 'mutation';
  name: string;
  args: string[];
  returns: string;
  example: string;
}
export type RulePart = 'status' | 'header' | 'json' | 'body' | 'time';
export interface CheckView {
  part: RulePart;
  path: string;
  op: 'equals' | 'not_equals' | 'contains' | 'exists' | 'less' | 'greater';
  expected: string;
}
export interface CaptureView {
  variable: string;
  part: RulePart;
  path: string;
}
export interface SavedRequestView extends HttpCallView {
  id: string;
  name: string;
  collection: string;
  checks?: CheckView[];
  captures?: CaptureView[];
}
export interface CookieView {
  domain: string;
  path: string;
  name: string;
  value: string;
  httpOnly: boolean;
}
export interface HttpOverview {
  variables: { name: string; value: string; source: 'service' | 'user'; secret: boolean }[];
  saved: SavedRequestView[];
  history: (HttpCallView & { at: string; status: number | null; ms: number })[];
  cookies: CookieView[];
  /** Collections kept as files in the project folder (.devdesk/requests/), for git. */
  shared?: string[];
}
export interface CheckResultView {
  label: string;
  passed: boolean;
  actual: string;
}
/** A log line any service printed while the request ran. */
export type TraceLine = LogLine;
interface ResultCommon {
  url: string;
  service: string | null;
  sentId: number;
  ms: number;
  checks: CheckResultView[];
  captured: { variable: string; found: boolean; value: string | null }[];
  trace?: TraceLine[];
  /** SQL the request made a Postgres of the project run (read from its log), and the rows it touched. */
  queries?: {
    service: string;
    at: string;
    ms: number;
    sql: string;
    params: string;
    shape: string;
    /** Where this query stands in the database's slowest-queries list, when that list is on. */
    slow?: { rank: number; calls: number; meanMs: number; totalMs: number };
  }[];
  /** "N+1": one query shape run many times by this request. The engine writes the advice. */
  repeats?: {
    service: string;
    shape: string;
    example: string;
    times: number;
    distinctValues: number;
    totalMs: number;
    before: string | null;
    identical: boolean;
    advice: string;
  }[];
  rows?: { service: string; schema: string; table: string; column: string; value: string; action: string }[];
}
export type HttpResultView =
  | (ResultCommon & { outcome: 'answered'; status: number; version: string; headers: HeaderView[]; body: string; binary: boolean; size: number })
  | (ResultCommon & { outcome: 'failed'; reason: string; explanation: string; detail: string });
export interface RunView {
  collection: string;
  passed: number;
  total: number;
  results: { id: string; name: string; method: string; url: string; ok: boolean; outcome?: string; status?: number; ms?: number; checks?: CheckResultView[]; explanation?: string; error?: string }[];
}
export type DataAnswer<T> = { ok: true; value: T } | { ok: false; error: string };
/** One card of the "Add project" wizard: exactly what devdesk.yaml will say about the service. */
export interface SetupService {
  name: string;
  kind: 'command' | 'container';
  command: string;
  directory: string;
  image: string;
  /** "3000" for a command; "55432:5432" (host:container) for a container. */
  ports: string[];
  volumes: string[];
  environment: Record<string, string>;
  /** other service → started | healthy | completed_successfully */
  dependsOn: Record<string, string>;
  health: { type: string; target: string; startPeriod: string };
  restart: string;
  origin: string;
  notes: string[];
  extras: Record<string, string>;
  /** Set by the engine: a database or mail catcher (the wizard draws it as one). */
  dataStore?: boolean;
}
export interface SetupDraft {
  name: string;
  envFiles: string[];
  services: SetupService[];
  notes: string[];
  /** Parts of devdesk.yaml the wizard doesn't edit (profiles:), kept as written. */
  kept?: string;
}
export interface SetupScan {
  path: string;
  mode: 'new' | 'edit';
  draft: SetupDraft;
  found: string[];
  suggestions: SetupService[];
  databaseKinds: string[];
  /** AWS services the code uses, and the local stand-in to add for them. */
  cloud?: { services: string[]; foundIn: string[]; suggestion: string | null };
}
export interface SetupPreview {
  yaml: string;
  problems: (ConfigProblem & { error?: string })[];
  commands: string[];
  env: {
    /** value is null for secrets: the engine never sends them. */
    variables: { key: string; file: string; value: string | null; secret: boolean; usedBy: string[] }[];
    missing: { key: string; reason: string }[];
    warnings: string[];
  };
  exists: boolean;
}

/** One step of "Set up" for a fresh clone; the command is shown before anything runs. */
export interface FreshStep {
  id: string;
  title: string;
  kind: 'env' | 'run' | 'job';
  directory?: string;
  command?: string;
  file?: string;
  questions?: { key: string; example: string; secret: boolean }[];
  service?: string;
}
export interface FreshPlan {
  path: string;
  name: string;
  steps: FreshStep[];
  notes: string[];
  draft: SetupDraft;
  running: boolean;
}
export interface FreshRun {
  path: string;
  running: boolean;
  steps: { id: string; title: string; status: string; detail: string; output: string[] }[];
}

/** The .env editor's view of one env file. Secret values are null until revealed. */
export interface EnvFileView {
  name: string;
  exists: boolean;
  example: boolean;
  /** Listed in devdesk.yaml's env_files: every service gets these. */
  everyService: boolean;
  variables: { key: string; value: string | null; secret: boolean; usedBy: string[] }[];
}
export interface EnvOverview {
  files: EnvFileView[];
  missing: { key: string; reason: string }[];
  warnings: string[];
  running: boolean;
  restartNeeded?: boolean;
}

/** One run of a task. status: waiting (for needed services), running, ok, failed, blocked, cancelled. */
export interface TaskRun {
  id: string;
  task: string;
  status: string;
  detail: string;
  started: string;
  ended: string | null;
  seconds: number;
  exitCode: number | null;
  output?: string[];
  /** Passed and failed counts read from the output, when the test tool's summary was recognised. */
  report: TestReport | null;
}
export interface TestReport {
  passed: number;
  failed: number;
  skipped: number;
  unit: string;
  failures: string[];
}
export interface TaskView {
  name: string;
  command: string;
  directory: string;
  needs: string[];
  description: string;
  /** A warning to show before running, from devdesk.yaml. */
  confirm: string | null;
  last: TaskRun | null;
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
  /** The latest crash or failed start, from the engine's state events: the app pops it up. */
  readonly crash = signal<{ service: string; label: string; detail: string; reason: string; at: number } | null>(null);
  /** What the doctor checked and found fine ("Node 22.3.0 installed…"), shown as ✓ lines on the Diagnosis page. */
  readonly passed = signal<string[] | null>(null);
  readonly resources = signal<Record<string, Resources>>({});
  readonly warnings = signal<string[]>([]);

  readonly allFindings = computed(() => Object.values(this.findings()).flat());
  readonly running = computed(() =>
    (this.project()?.services ?? []).some((s) =>
      ['starting', 'running', 'healthy', 'unhealthy', 'stopping', 'waiting'].includes(s.state.label),
    ),
  );

  private connection: Connection | null = null;
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
    // A window opened onto another engine (a remote one, through an SSH tunnel) says which in its address.
    const given = new URLSearchParams(location.search);
    if (Number(given.get('port')) && given.get('token')) {
      return { port: Number(given.get('port')), token: given.get('token')! };
    }
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
        this.passed.set(null);
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
        if (['crashed', 'failed', 'crash-loop'].includes(e.state.label)) {
          this.crash.set({ service: e.service, label: e.state.label, detail: e.state.detail ?? '', reason: e.reason ?? '', at: Date.now() });
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

  /** {@code stopCurrent}: stop the running project first (the switcher's "stop it and open that one"). */
  async open(
    path: string,
    stopCurrent = false,
  ): Promise<{ ok: true } | { ok: false; error: string; problems?: ConfigProblem[]; missing: boolean }> {
    const r = await this.call('POST', '/project/open', stopCurrent ? { path, stopCurrent } : { path });
    // 404: the folder has no devdesk.yaml (or doesn't exist): the setup wizard takes over from there.
    return r.ok ? { ok: true } : { ok: false, error: r.body.error, problems: r.body.problems, missing: r.status === 404 };
  }

  // ---- the "Add project" wizard (all thinking happens in the engine) ----
  setupScan(path: string): Promise<DataAnswer<SetupScan>> {
    return this.data('POST', '/setup/scan', { path }, (b) => b);
  }
  setupDatabase(kind: string, draft: SetupDraft): Promise<DataAnswer<SetupService>> {
    return this.data('POST', '/setup/database', { kind, draft }, (b) => b);
  }
  setupPreview(path: string, draft: SetupDraft): Promise<DataAnswer<SetupPreview>> {
    return this.data('POST', '/setup/preview', { path, draft }, (b) => b);
  }
  setupTest(path: string, draft: SetupDraft): Promise<DataAnswer<unknown>> {
    return this.data('POST', '/setup/test', { path, draft }, (b) => b);
  }
  setupWrite(path: string, draft: SetupDraft, replace: boolean): Promise<DataAnswer<ProjectView>> {
    return this.data('POST', '/setup/write', { path, draft, replace }, (b) => b);
  }
  setupVariable(path: string, file: string, key: string, value: string): Promise<DataAnswer<unknown>> {
    return this.data('POST', '/setup/variable', { path, file, key, value }, (b) => b);
  }
  /** Past output (kept some days by the engine): newest first. */
  searchLogs(
    q: string,
    service: string,
    errors: boolean,
    hours: number,
  ): Promise<DataAnswer<{ lines: { at: string; service: string; stream: string; text: string }[]; more: boolean }>> {
    const p = new URLSearchParams({ q, service, errors: String(errors), hours: String(hours) });
    return this.data('GET', `/logs/search?${p}`, undefined, (b) => b);
  }
  logsAround(service: string, at: string): Promise<DataAnswer<{ at: string; service: string; stream: string; text: string }[]>> {
    const p = new URLSearchParams({ service, at });
    return this.data('GET', `/logs/around?${p}`, undefined, (b) => b.lines);
  }
  /** A terminal in a service ("Shell"): a WebSocket of its own; the engine decides what runs and checks trust. */
  terminalUrl(service: string, columns: number, rows: number): string | null {
    const c = this.connection;
    if (!c) return null;
    const q = new URLSearchParams({ token: c.token, service, columns: String(columns), rows: String(rows) });
    return `ws://127.0.0.1:${c.port}/api/v1/terminal?${q}`;
  }

  /** Which services restart when their files change, and which folders or files are watched. */
  watchStatus(): Promise<DataAnswer<Record<string, { on: boolean; folders: string[] }>>> {
    return this.data('GET', '/watch', undefined, (b) => b);
  }
  setWatch(service: string, on: boolean): Promise<DataAnswer<Record<string, { on: boolean; folders: string[] }>>> {
    return this.data('POST', `/services/${encodeURIComponent(service)}/watch`, { on }, (b) => b);
  }
  remote(): Promise<DataAnswer<RemoteView>> {
    return this.data('GET', '/remote', undefined, (b) => b);
  }
  setRemote(target: { user: string; host: string; port: number; folder: string; identityFile?: string }): Promise<DataAnswer<RemoteView>> {
    return this.data('POST', '/remote/target', target, (b) => b);
  }
  runRemote(mode: 'deploy' | 'devbox'): Promise<DataAnswer<RemoteView>> {
    return this.data('POST', '/remote/run', { mode }, (b) => b);
  }
  stopRemote(stopThere: boolean): Promise<DataAnswer<RemoteView>> {
    return this.data('POST', '/remote/stop', { stopRemote: stopThere }, (b) => b);
  }
  stress(): Promise<DataAnswer<StressView>> {
    return this.data('GET', '/stress', undefined, (b) => b);
  }
  startStress(plan: { method: string; url: string; body: string; headers: Record<string, string>; maxConcurrency: number; seconds: number }): Promise<DataAnswer<StressView>> {
    return this.data('POST', '/stress/start', plan, (b) => b);
  }
  stopStress(): Promise<DataAnswer<StressView>> {
    return this.data('POST', '/stress/cancel', {}, (b) => b);
  }
  recorder(): Promise<DataAnswer<{ targets: { service: string; ports: number[] }[]; recordings: RecordingView[] }>> {
    return this.data('GET', '/recorder', undefined, (b) => b);
  }
  startRecording(service: string, port: number | null, listen: number | null): Promise<DataAnswer<RecordingView>> {
    return this.data('POST', '/recorder/start', { service, ...(port ? { port } : {}), ...(listen ? { listen } : {}) }, (b) => b);
  }
  stopRecording(id: string): Promise<DataAnswer<RecordingView>> {
    return this.data('POST', `/recorder/${encodeURIComponent(id)}/stop`, {}, (b) => b);
  }
  recording(id: string): Promise<DataAnswer<RecordingView & { calls: RecordedCall[] }>> {
    return this.data('GET', `/recorder/${encodeURIComponent(id)}`, undefined, (b) => b);
  }
  replay(id: string): Promise<DataAnswer<{ results: ReplayResult[]; same: number; total: number }>> {
    return this.data('POST', `/recorder/${encodeURIComponent(id)}/replay`, {}, (b) => b);
  }
  deleteRecording(id: string): Promise<DataAnswer<unknown>> {
    return this.data('POST', `/recorder/${encodeURIComponent(id)}/delete`, {}, (b) => b);
  }
  brokers(): Promise<DataAnswer<BrokerView[]>> {
    return this.data('GET', '/queues', undefined, (b) => b.brokers);
  }
  queues(service: string): Promise<DataAnswer<QueueSummary[]>> {
    return this.data('GET', `/queues/${encodeURIComponent(service)}`, undefined, (b) => b.queues);
  }
  queueMessages(service: string, queue: string, max: number): Promise<DataAnswer<QueueMessage[]>> {
    return this.data('GET', `/queues/${encodeURIComponent(service)}/${encodeURIComponent(queue)}/messages?max=${max}`, undefined, (b) => b.messages);
  }
  queueReaders(service: string, queue: string): Promise<DataAnswer<{ readers: QueueReader[]; connected: Record<string, number> }>> {
    return this.data('GET', `/queues/${encodeURIComponent(service)}/${encodeURIComponent(queue)}/readers`, undefined, (b) => b);
  }
  sendToQueue(service: string, queue: string, body: string, key: string): Promise<DataAnswer<unknown>> {
    return this.data('POST', `/queues/${encodeURIComponent(service)}/${encodeURIComponent(queue)}/publish`, { body, key }, (b) => b);
  }
  buckets(service: string): Promise<DataAnswer<{ name: string; created: string }[]>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/objects/buckets`, undefined, (b) => b.buckets);
  }
  createBucket(service: string, name: string): Promise<DataAnswer<unknown>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/objects/buckets`, { name }, (b) => b);
  }
  bucketFiles(service: string, bucket: string, prefix: string, after?: string): Promise<DataAnswer<BucketListing>> {
    const q = new URLSearchParams({ bucket, prefix, after: after ?? '' });
    return this.data('GET', `/data/${encodeURIComponent(service)}/objects?${q}`, undefined, (b) => b);
  }
  filePreview(service: string, bucket: string, key: string): Promise<DataAnswer<FilePreview>> {
    const q = new URLSearchParams({ bucket, key });
    return this.data('GET', `/data/${encodeURIComponent(service)}/objects/preview?${q}`, undefined, (b) => b);
  }
  downloadFile(service: string, bucket: string, key: string, path: string): Promise<DataAnswer<{ saved: string; bytes: number }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/objects/download`, { bucket, key, path }, (b) => b);
  }
  uploadFile(service: string, bucket: string, key: string, path: string): Promise<DataAnswer<{ key: string; bytes: number }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/objects/upload`, { bucket, key, path }, (b) => b);
  }
  deleteFile(service: string, bucket: string, key: string): Promise<DataAnswer<unknown>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/objects/delete`, { bucket, key }, (b) => b);
  }
  searchIndices(service: string): Promise<DataAnswer<{ name: string; documents: number; bytes: number; health: string }[]>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/search/indices`, undefined, (b) => b.indices);
  }
  searchMapping(service: string, index: string): Promise<DataAnswer<string>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/search/mapping?${new URLSearchParams({ index })}`, undefined, (b) => b.mapping);
  }
  search(service: string, index: string, query: string, from: number, size: number): Promise<DataAnswer<SearchHits>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/search`, { index, query, from, size }, (b) => b);
  }
  slowQueries(service: string): Promise<DataAnswer<SlowQueriesView>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/slow-queries`, undefined, (b) => b);
  }
  slowQueriesStep(service: string, step: 'load' | 'create' | 'reset'): Promise<DataAnswer<unknown>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/slow-queries`, { step }, (b) => b);
  }
  /** Packs states, findings, logs, timeline and settings (secrets out) into one zip; no path = DevDesk's folder. */
  crashBundle(path: string | null): Promise<DataAnswer<{ path: string; bytes: number }>> {
    return this.data('POST', '/project/bundle', path ? { path } : {}, (b) => b);
  }
  /** What changed since the project last started cleanly (config, .env keys, lock files, images, tools, git). */
  changes(): Promise<DataAnswer<{ known: boolean; message?: string; since?: string; changes?: { area: string; what: string; detail: string }[] }>> {
    return this.data('GET', '/project/changes', undefined, (b) => b);
  }
  migrations(): Promise<DataAnswer<MigrationSource[]>> {
    return this.data('GET', '/migrations', undefined, (b) => b.sources);
  }
  fakePreview(service: string, schema: string, table: string): Promise<DataAnswer<FakePreview>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/fake/preview`, { schema, table }, (b) => b);
  }
  fakeInsert(service: string, schema: string, table: string, count: number): Promise<DataAnswer<{ inserted: number; ms: number }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/fake/insert`, { schema, table, count }, (b) => b);
  }
  snapshots(): Promise<DataAnswer<SnapshotView[]>> {
    return this.data('GET', '/snapshots', undefined, (b) => b.snapshots);
  }
  saveSnapshot(label: string): Promise<DataAnswer<SnapshotView>> {
    return this.data('POST', '/snapshots', { label }, (b) => b);
  }
  restoreSnapshot(id: string, saveFirst: boolean): Promise<DataAnswer<SnapshotRestore>> {
    return this.data('POST', `/snapshots/${encodeURIComponent(id)}/restore`, { saveFirst }, (b) => b);
  }
  deleteSnapshot(id: string): Promise<DataAnswer<SnapshotView[]>> {
    return this.data('POST', `/snapshots/${encodeURIComponent(id)}/delete`, {}, (b) => b.snapshots);
  }
  /** Each service's tests: the same shape as tasks, named after the service. */
  tests(): Promise<DataAnswer<TaskView[]>> {
    return this.data('GET', '/tests', undefined, (b) => b.tasks);
  }
  testRuns(service: string): Promise<DataAnswer<TaskRun[]>> {
    return this.data('GET', `/tests/${encodeURIComponent(service)}`, undefined, (b) => b.runs);
  }
  runTest(service: string): Promise<DataAnswer<TaskRun>> {
    return this.data('POST', `/tests/${encodeURIComponent(service)}/run`, {}, (b) => b);
  }
  runAllTests(): Promise<DataAnswer<string[]>> {
    return this.data('POST', '/tests/run-all', {}, (b) => b.queued);
  }
  async cancelTest(service: string): Promise<void> {
    await this.call('POST', `/tests/${encodeURIComponent(service)}/cancel`, {});
  }
  async cancelAllTests(): Promise<void> {
    await this.call('POST', '/tests/cancel-all', {});
  }
  tasks(): Promise<DataAnswer<TaskView[]>> {
    return this.data('GET', '/tasks', undefined, (b) => b.tasks);
  }
  taskRuns(name: string): Promise<DataAnswer<TaskRun[]>> {
    return this.data('GET', `/tasks/${encodeURIComponent(name)}`, undefined, (b) => b.runs);
  }
  runTask(name: string): Promise<DataAnswer<TaskRun>> {
    return this.data('POST', `/tasks/${encodeURIComponent(name)}/run`, {}, (b) => b);
  }
  async cancelTask(name: string): Promise<void> {
    await this.call('POST', `/tasks/${encodeURIComponent(name)}/cancel`, {});
  }
  envOverview(): Promise<DataAnswer<EnvOverview>> {
    return this.data('GET', '/env', undefined, (b) => b);
  }
  envSet(file: string, key: string, value: string): Promise<DataAnswer<EnvOverview>> {
    return this.data('POST', '/env/set', { file, key, value }, (b) => b);
  }
  envDelete(file: string, key: string): Promise<DataAnswer<EnvOverview>> {
    return this.data('POST', '/env/delete', { file, key }, (b) => b);
  }
  envReveal(file: string, key: string): Promise<DataAnswer<string>> {
    return this.data('POST', '/env/reveal', { file, key }, (b) => b.value);
  }
  /** Switches profile ("" = default); the engine reopens the project with it. */
  async selectProfile(profile: string): Promise<string | null> {
    const r = await this.call('POST', '/project/profile', { profile });
    if (r.ok) this.project.set(r.body);
    return r.ok ? null : r.body.error;
  }
  freshPlan(path: string): Promise<DataAnswer<FreshPlan>> {
    return this.data('POST', '/setup/fresh', { path }, (b) => b);
  }
  freshRun(path: string, steps: string[], answers: Record<string, string>): Promise<DataAnswer<FreshRun>> {
    return this.data('POST', '/setup/fresh/run', { path, steps, answers }, (b) => b);
  }
  freshStatus(): Promise<DataAnswer<FreshRun>> {
    return this.data('GET', '/setup/fresh/run', undefined, (b) => b);
  }
  async freshCancel(): Promise<void> {
    await this.call('POST', '/setup/fresh/cancel', {});
  }
  async setupDiscard(): Promise<void> {
    await this.call('POST', '/setup/discard', {});
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
  /** The doctor: the pre-start checks plus tool versions, Docker, images, packages and disk space. */
  async doctor(): Promise<void> {
    const r = await this.call('GET', '/project/doctor');
    if (r.ok) {
      this.preflight.set(r.body.problems);
      this.passed.set(r.body.passed);
    }
  }
  setupDoctor(path: string, draft: SetupDraft): Promise<DataAnswer<{ problems: Finding[]; passed: string[]; skipped?: string }>> {
    return this.data('POST', '/setup/doctor', { path, draft }, (b) => b);
  }

  async serviceAction(name: string, action: 'start' | 'stop' | 'restart'): Promise<string | null> {
    const r = await this.call('POST', `/services/${encodeURIComponent(name)}/${action}`);
    return r.ok ? null : r.body.error;
  }
  async details(name: string): Promise<ServiceDetails | null> {
    const r = await this.call('GET', `/services/${encodeURIComponent(name)}`);
    return r.ok ? r.body : null;
  }
  /** Asks the engine for one value; it's never sent unless the user clicks for it. */
  async reveal(name: string, key: string): Promise<string | null> {
    const r = await this.call('POST', `/services/${encodeURIComponent(name)}/env/${encodeURIComponent(key)}/reveal`);
    return r.ok ? r.body.value : null;
  }
  // ---- Workbench: data ----
  async databases(): Promise<DatabaseView[]> {
    const r = await this.call('GET', '/data');
    return r.ok ? r.body.databases : [];
  }
  async tables(service: string): Promise<DataAnswer<TableView[]>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/tables`, undefined, (b) => b.tables);
  }
  async structure(service: string, schema: string, table: string): Promise<DataAnswer<StructureView>> {
    const q = `schema=${encodeURIComponent(schema)}&table=${encodeURIComponent(table)}`;
    return this.data('GET', `/data/${encodeURIComponent(service)}/structure?${q}`, undefined, (b) => b);
  }
  async rows(service: string, query: RowQueryView): Promise<DataAnswer<ResultView>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/rows`, query, (b) => b);
  }
  async query(service: string, sql: string): Promise<DataAnswer<ResultView>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/query`, { sql, maxRows: 1000 }, (b) => b);
  }
  /** The SQL a table form would run; nothing runs. table: the current name, '' for a new table. */
  async previewDesign(service: string, table: string, design: DesignView): Promise<DataAnswer<{ sql: string[]; notes: string[] }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/design/preview`, { table, design }, (b) => b);
  }
  /** Runs it, if it's still exactly the SQL the user saw. */
  async applyDesign(service: string, table: string, design: DesignView, sql: string[]): Promise<DataAnswer<unknown>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/design/apply`, { table, design, sql }, (b) => b);
  }
  async dropTable(service: string, schema: string, table: string, confirm: string): Promise<DataAnswer<unknown>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/drop-table`, { schema, table, confirm }, (b) => b);
  }
  async queryLogging(service: string): Promise<DataAnswer<{ supported: boolean; on: boolean }>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/query-logging`, undefined, (b) => b);
  }
  async setQueryLogging(service: string, on: boolean): Promise<DataAnswer<{ on: boolean }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/query-logging`, { on }, (b) => b);
  }
  async stats(service: string): Promise<DataAnswer<StatsView>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/stats`, undefined, (b) => b);
  }
  /** Every table of a schema ('' = all) with its columns and links, in levels for drawing. */
  async diagram(service: string, schema: string): Promise<DataAnswer<DiagramView>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/diagram?schema=${encodeURIComponent(schema)}`, undefined, (b) => b);
  }
  async plan(service: string, sql: string, analyze: boolean): Promise<DataAnswer<PlanView>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/plan`, { sql, analyze }, (b) => b);
  }
  async activity(service: string): Promise<DataAnswer<SessionView[]>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/activity`, undefined, (b) => b.sessions);
  }
  async stopSession(service: string, pid: number, terminate: boolean): Promise<DataAnswer<{ stopped: boolean }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/activity/${pid}/stop`, { terminate }, (b) => b);
  }
  /** A table (with the grid's filters) or a query's result, to a file the engine writes (path) or as text. */
  async exportData(service: string, what: (RowQueryView | { sql: string }) & { format: FileFormat; path?: string }): Promise<DataAnswer<ExportAnswer>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/export`, what, (b) => b);
  }
  async importData(service: string, what: { format: FileFormat; schema?: string; table?: string; path?: string; content?: string }): Promise<DataAnswer<{ rows: number; ms: number }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/import`, what, (b) => b);
  }
  async backups(service: string): Promise<DataAnswer<BackupView[]>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/backups`, undefined, (b) => b.backups);
  }
  async saveBackup(service: string, label: string): Promise<DataAnswer<BackupView>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/backups`, { label }, (b) => b);
  }
  async restoreBackup(service: string, name: string): Promise<DataAnswer<{ restored: string; ms: number }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/backups/${encodeURIComponent(name)}/restore`, {}, (b) => b);
  }
  async deleteBackup(service: string, name: string): Promise<DataAnswer<BackupView[]>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/backups/${encodeURIComponent(name)}/delete`, {}, (b) => b.backups);
  }
  async savedQueries(service: string): Promise<DataAnswer<SavedQueryView[]>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/queries`, undefined, (b) => b.queries);
  }
  async saveQuery(service: string, q: { id?: string; name: string; sql: string }): Promise<DataAnswer<{ id: string; queries: SavedQueryView[] }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/queries`, q, (b) => b);
  }
  async deleteQuery(service: string, id: string): Promise<DataAnswer<SavedQueryView[]>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/queries/${encodeURIComponent(id)}/delete`, {}, (b) => b.queries);
  }
  /** The only write: every change in one transaction, all or nothing. */
  async saveChanges(service: string, schema: string, table: string, changes: RowChangeView[]): Promise<DataAnswer<{ rows: number; ms: number }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/changes`, { schema, table, changes }, (b) => b);
  }
  // ---- Workbench: Redis ----
  async keys(service: string, pattern: string, cursor: string, count = 500): Promise<DataAnswer<{ keys: KeyInfoView[]; next: string }>> {
    const q = `pattern=${encodeURIComponent(pattern)}&cursor=${cursor}&count=${count}`;
    return this.data('GET', `/data/${encodeURIComponent(service)}/keys?${q}`, undefined, (b) => b);
  }
  // ---- MongoDB ----
  async mongoDatabases(service: string): Promise<DataAnswer<{ name: string; sizeBytes: number }[]>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/mongo/databases`, undefined, (b) => b.databases);
  }
  async mongoCollections(service: string, db: string): Promise<DataAnswer<{ name: string; documents: number }[]>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/mongo/collections?db=${encodeURIComponent(db)}`, undefined, (b) => b.collections);
  }
  async mongoFind(service: string, q: { db: string; collection: string; filter: string; sort: string; skip: number; limit: number }): Promise<DataAnswer<{ documents: string[]; matching: number; ms: number }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/mongo/find`, q, (b) => b);
  }
  async mongoAction(service: string, action: 'insert' | 'replace' | 'delete', body: { db: string; collection: string; id?: string; document?: string }): Promise<DataAnswer<unknown>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/mongo/${action}`, body, (b) => b);
  }
  async stream(service: string, key: string): Promise<DataAnswer<StreamInfoView>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/stream?key=${encodeURIComponent(key)}`, undefined, (b) => b);
  }
  async redisServer(service: string): Promise<DataAnswer<ServerStatsView>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/server`, undefined, (b) => b);
  }
  /** No path: the file's text comes back (browser fallback). */
  async exportKeys(service: string, pattern: string, path: string): Promise<DataAnswer<{ keys: number; path?: string; content?: string }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/keys/export`, path ? { pattern, path } : { pattern }, (b) => b);
  }
  async importKeys(service: string, from: { path: string } | { content: string }, replace: boolean): Promise<DataAnswer<{ keys: number; written: number; skipped: number }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/keys/import`, { ...from, replace }, (b) => b);
  }
  async key(service: string, name: string): Promise<DataAnswer<KeyValueView>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/key?name=${encodeURIComponent(name)}`, undefined, (b) => b);
  }
  async deleteKey(service: string, name: string): Promise<DataAnswer<{ existed: boolean }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/key/delete`, { name }, (b) => b);
  }
  /** seconds null: the key never expires. */
  async expireKey(service: string, name: string, seconds: number | null): Promise<DataAnswer<{ existed: boolean }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/key/expire`, { name, seconds }, (b) => b);
  }
  /** One change to a key's value; see the ops in daemon/ApiServer.redisEdit. Creates the key if needed. */
  async editKey(service: string, name: string, edit: RedisEdit): Promise<DataAnswer<{ edited: boolean }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/key/edit`, { name, edit }, (b) => b);
  }
  /** The engine answers {confirm: why} for risky commands; send again with confirmed after asking the user. */
  async redisCommand(service: string, line: string, confirmed = false): Promise<DataAnswer<{ reply?: string; confirm?: string }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/command`, { line, confirmed }, (b) => b);
  }

  async redisMemory(service: string, pattern: string): Promise<DataAnswer<MemoryView>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/memory?pattern=${encodeURIComponent(pattern)}`, undefined, (b) => b);
  }
  async slowLog(service: string): Promise<DataAnswer<SlowLogView>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/slowlog`, undefined, (b) => b);
  }
  async resetSlowLog(service: string): Promise<DataAnswer<unknown>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/slowlog/reset`, {}, (b) => b);
  }
  /** How many keys match, and the first of them: shown before deleting by pattern. */
  async matching(service: string, pattern: string): Promise<DataAnswer<{ count: number; sample: string[] }>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/matching?pattern=${encodeURIComponent(pattern)}`, undefined, (b) => b);
  }
  async deleteMatching(service: string, pattern: string): Promise<DataAnswer<{ deleted: number }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/keys/delete`, { pattern }, (b) => b);
  }
  async publish(service: string, channel: string, message: string): Promise<DataAnswer<{ receivers: number }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/publish`, { channel, message }, (b) => b);
  }
  async watch(service: string, what: { kind: 'monitor' } | { kind: 'subscribe'; patterns: string[] }): Promise<DataAnswer<{ id: string }>> {
    return this.data('POST', `/data/${encodeURIComponent(service)}/watch`, what, (b) => b);
  }
  async watchEvents(service: string, id: string, after: number): Promise<DataAnswer<{ events: WatchEventView[]; ended: string | null }>> {
    return this.data('GET', `/data/${encodeURIComponent(service)}/watch/${id}?after=${after}`, undefined, (b) => b);
  }
  async stopWatch(service: string, id: string): Promise<void> {
    await this.call('POST', `/data/${encodeURIComponent(service)}/watch/${id}/stop`, {});
  }

  // ---- Workbench: API client ----
  async httpOverview(): Promise<HttpOverview | null> {
    const r = await this.call('GET', '/http');
    return r.ok ? r.body : null;
  }
  async httpSend(call: HttpCallView & { checks?: CheckView[]; captures?: CaptureView[] }): Promise<DataAnswer<HttpResultView>> {
    return this.data('POST', '/http/send', call, (b) => b);
  }
  async httpSave(req: HttpCallView & { id?: string; name: string; collection: string; checks?: CheckView[]; captures?: CaptureView[] }): Promise<DataAnswer<HttpOverview & { id: string }>> {
    return this.data('POST', '/http/saved', req, (b) => b);
  }
  /** Sends every saved request of a collection in order, with their checks. */
  async httpRun(collection: string): Promise<DataAnswer<RunView>> {
    return this.data('POST', '/http/run', { collection }, (b) => b);
  }
  async httpShare(collection: string, shared: boolean): Promise<DataAnswer<HttpOverview>> {
    return this.data('POST', '/http/share', { collection, shared }, (b) => b);
  }
  async httpClearCookies(): Promise<DataAnswer<HttpOverview>> {
    return this.data('POST', '/http/cookies/clear', {}, (b) => b);
  }
  async httpImportOpenApi(from: { service: string } | { content: string } | { path: string }): Promise<DataAnswer<HttpOverview & { imported: number; from: string }>> {
    return this.data('POST', '/http/import/openapi', from, (b) => b);
  }
  async httpImportPostman(from: { content: string } | { path: string }): Promise<DataAnswer<HttpOverview & { imported: number; from: string }>> {
    return this.data('POST', '/http/import/postman', from, (b) => b);
  }
  async httpFromCurl(command: string): Promise<DataAnswer<HttpCallView>> {
    return this.data('POST', '/http/import/curl', { command }, (b) => b);
  }
  /** The request as a curl command, variables filled in. */
  async httpCurl(call: HttpCallView): Promise<DataAnswer<{ curl: string; missing: string[] }>> {
    return this.data('POST', '/http/curl', call, (b) => b);
  }
  // ---- mail (the project's Mailpit) ----
  async mailCatchers(): Promise<MailCatcherView[]> {
    const r = await this.call('GET', '/mail');
    return r.ok ? r.body.catchers : [];
  }
  async mails(service: string, search: string, limit = 100): Promise<DataAnswer<MailPage>> {
    const q = `search=${encodeURIComponent(search)}&limit=${limit}`;
    return this.data('GET', `/mail/${encodeURIComponent(service)}/messages?${q}`, undefined, (b) => b);
  }
  async mail(service: string, id: string): Promise<DataAnswer<MailView>> {
    return this.data('GET', `/mail/${encodeURIComponent(service)}/messages/${encodeURIComponent(id)}`, undefined, (b) => b);
  }
  /** No ids: every mail. */
  async deleteMails(service: string, ids: string[]): Promise<DataAnswer<unknown>> {
    return this.data('POST', `/mail/${encodeURIComponent(service)}/delete`, { ids }, (b) => b);
  }
  async saveAttachment(service: string, id: string, part: string, path: string): Promise<DataAnswer<{ path: string; size: number }>> {
    return this.data('POST', `/mail/${encodeURIComponent(service)}/messages/${encodeURIComponent(id)}/parts/${encodeURIComponent(part)}/save`, { path }, (b) => b);
  }
  /** In a browser (no save dialog): the attachment's bytes, for the page to download. */
  async attachmentBlob(service: string, id: string, part: string): Promise<Blob | null> {
    const c = this.connection;
    if (!c) return null;
    const res = await fetch(`http://127.0.0.1:${c.port}/api/v1/mail/${encodeURIComponent(service)}/messages/${encodeURIComponent(id)}/parts/${encodeURIComponent(part)}`, {
      headers: { Authorization: `Bearer ${c.token}` },
    });
    return res.ok ? res.blob() : null;
  }

  // ---- mock server ----
  async mock(after: number): Promise<DataAnswer<MockOverview>> {
    return this.data('GET', `/http/mock?after=${after}`, undefined, (b) => b);
  }
  async saveMockRoute(r: MockRoute): Promise<DataAnswer<MockOverview>> {
    return this.data('POST', '/http/mock/routes', r, (b) => b);
  }
  async deleteMockRoute(id: string): Promise<DataAnswer<MockOverview>> {
    return this.data('POST', `/http/mock/routes/${encodeURIComponent(id)}/delete`, {}, (b) => b);
  }
  async startMock(port: number): Promise<DataAnswer<MockOverview>> {
    return this.data('POST', '/http/mock/start', { port }, (b) => b);
  }
  async stopMock(): Promise<DataAnswer<MockOverview>> {
    return this.data('POST', '/http/mock/stop', {}, (b) => b);
  }

  // ---- WebSocket connections (kept by the engine; the app polls what happened) ----
  async openSocket(call: HttpCallView): Promise<DataAnswer<{ id: string }>> {
    return this.data('POST', '/http/socket', call, (b) => b);
  }
  async pollSocket(id: string, after: number): Promise<DataAnswer<{ state: 'connecting' | 'open' | 'closed'; events: SocketEvent[] }>> {
    return this.data('GET', `/http/socket/${id}?after=${after}`, undefined, (b) => b);
  }
  async sendSocket(id: string, text: string): Promise<DataAnswer<unknown>> {
    return this.data('POST', `/http/socket/${id}/send`, { text }, (b) => b);
  }
  async closeSocket(id: string): Promise<void> {
    await this.call('POST', `/http/socket/${id}/close`, {});
  }
  /** The queries and mutations a GraphQL endpoint offers, each with an example query. */
  async graphQlSchema(call: HttpCallView): Promise<DataAnswer<{ operations: GraphQlOperation[] }>> {
    return this.data('POST', '/http/graphql/schema', call, (b) => b);
  }
  /** A request a crash line points at, as it was typed. */
  async httpSent(id: number): Promise<DataAnswer<HttpCallView>> {
    return this.data('GET', `/http/sent/${id}`, undefined, (b) => b);
  }
  async httpDelete(id: string): Promise<DataAnswer<HttpOverview>> {
    return this.data('POST', `/http/saved/${encodeURIComponent(id)}/delete`, {}, (b) => b);
  }
  async httpVariables(variables: Record<string, string>): Promise<DataAnswer<HttpOverview>> {
    return this.data('POST', '/http/variables', { variables }, (b) => b);
  }

  private async data<T>(method: string, path: string, body: unknown, pick: (b: any) => T): Promise<DataAnswer<T>> {
    const r = await this.call(method, path, body);
    return r.ok ? { ok: true, value: pick(r.body) } : { ok: false, error: r.body?.error ?? 'the engine did not answer' };
  }

  async ports(): Promise<PortRow[] | null> {
    const r = await this.call('GET', '/ports');
    return r.ok ? r.body.ports : null;
  }
  async history(hours: number): Promise<History | null> {
    const r = await this.call('GET', `/project/history?hours=${hours}`);
    return r.ok ? r.body : null;
  }
  /** The last startups, newest first: per service, when it was asked to start, started, ran and was ready. */
  async startups(): Promise<Startup[] | null> {
    const r = await this.call('GET', '/project/startups');
    return r.ok ? r.body.startups : null;
  }
  async machine(): Promise<MachineView | null> {
    const r = await this.call('GET', '/project/machine');
    return r.ok ? r.body : null;
  }

  private async call(method: string, path: string, body?: unknown): Promise<{ ok: boolean; status: number; body: any }> {
    const c = this.connection;
    if (!c) return { ok: false, status: 0, body: { error: 'not connected to the engine' } };
    const res = await fetch(`http://127.0.0.1:${c.port}/api/v1${path}`, {
      method,
      headers: { Authorization: `Bearer ${c.token}`, 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { ok: res.ok, status: res.status, body: await res.json() };
  }
}
