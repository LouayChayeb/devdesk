import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { AuthView, BodyType, CaptureView, CheckView, Engine, GraphQlOperation, HttpCallView, HttpResultView, RulePart, SocketEvent } from './engine';
import { Requests } from './requests';
import { Catalog } from './catalog';
import { Doc, Workbench } from './workbench';
import { Icon } from './icon';

export const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];

/** A request as a tab holds it before it's saved, or as it was saved. */
export type RequestDraft = Partial<HttpCallView> & { id?: string; name?: string; collection?: string; checks?: CheckView[]; captures?: CaptureView[]; savedAs?: string };

/** One request tab: what's typed, the last answer, and which saved request it is (if any). */
export class RequestState {
  readonly method = signal('GET');
  readonly url = signal('');
  readonly headers = signal<{ name: string; value: string; on: boolean }[]>([]);
  readonly body = signal('');
  readonly bodyType = signal<BodyType>('none');
  readonly variables = signal('');
  readonly operations = signal<GraphQlOperation[] | null>(null);
  /** A ws:// request's connection: kept by the engine, its events polled here. */
  readonly socketId = signal<string | null>(null);
  readonly socketState = signal<'connecting' | 'open' | 'closed' | null>(null);
  readonly socketEvents = signal<SocketEvent[]>([]);
  readonly outgoing = signal('');
  readonly form = signal<{ name: string; value: string; file: boolean; on: boolean }[]>([]);
  readonly auth = signal<AuthView>({ type: 'none' });
  readonly checks = signal<CheckView[]>([]);
  readonly captures = signal<CaptureView[]>([]);
  readonly part = signal<'params' | 'headers' | 'body' | 'auth' | 'checks' | 'capture'>('params');
  readonly result = signal<HttpResultView | null>(null);
  readonly error = signal<string | null>(null);
  readonly sending = signal(false);
  readonly view = signal<'body' | 'headers' | 'trace' | 'sql'>('body');
  readonly pretty = signal(true);
  readonly savedId = signal<string | null>(null);
  readonly name = signal('');
  readonly collection = signal('');
  readonly saving = signal(false);
  /** The request as last saved, to tell whether it changed since. */
  readonly savedAs = signal<string>('');

  constructor(call?: RequestDraft) {
    if (!call) return;
    this.method.set(call.method ?? 'GET');
    this.url.set(call.url ?? '');
    this.headers.set((call.headers ?? []).map((h) => ({ ...h, on: true })));
    this.body.set(call.body ?? '');
    this.bodyType.set(call.bodyType ?? (call.body ? 'text' : 'none'));
    this.form.set((call.form ?? []).map((f) => ({ ...f, on: true })));
    this.variables.set(call.variables ?? '');
    this.auth.set(call.auth ?? { type: 'none' });
    this.checks.set(call.checks ?? []);
    this.captures.set(call.captures ?? []);
    if (this.bodyType() !== 'none') this.part.set('body');
    if (call.id) {
      this.savedId.set(call.id);
      this.name.set(call.name ?? '');
      this.collection.set(call.collection ?? '');
      this.savedAs.set(call.savedAs ?? this.fingerprint());
    }
  }

  /** What comes back with the project's tabs: the request as typed, and whether it differed from the saved one. */
  snapshot(): RequestDraft {
    return { ...this.full(), id: this.savedId() ?? undefined, name: this.name(), collection: this.collection(), savedAs: this.savedAs() };
  }

  call(): HttpCallView {
    return {
      method: this.method(),
      url: this.url(),
      headers: this.headers().filter((h) => h.on && h.name.trim()).map(({ name, value }) => ({ name, value })),
      bodyType: this.bodyType(),
      body: this.body() === '' ? null : this.body(),
      ...(this.bodyType() === 'graphql' ? { variables: this.variables() } : {}),
      form: this.form().filter((f) => f.on && f.name.trim()).map(({ name, value, file }) => ({ name, value, file })),
      auth: this.auth(),
    };
  }

  /** The request plus what to do with its answer. */
  full() {
    return { ...this.call(), checks: this.checks().filter((c) => c.part), captures: this.captures().filter((c) => c.variable.trim()) };
  }

  fingerprint(): string {
    return JSON.stringify([this.full(), this.name(), this.collection()]);
  }
}

/** The request builder and response viewer. Requests are sent by the engine, which also explains failures. */
@Component({
  selector: 'dd-request-view',
  imports: [Icon],
  templateUrl: './request-view.html',
  styleUrl: './request-view.css',
  host: { '(keydown)': 'key($event)' },
})
export class RequestView {
  readonly engine = inject(Engine);
  readonly requests = inject(Requests);
  readonly catalog = inject(Catalog);
  readonly wb = inject(Workbench);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();
  readonly variables = output<void>();
  /** The service the request went to: the app shows its logs right below. */
  readonly logs = output<string>();
  /** Opens another tab (the mock server after "Mock this"). */
  readonly show = output<Doc>();
  readonly methods = METHODS;
  /** Texts with double braces, which a template would read as its own placeholders. */
  readonly hints = {
    token: '{{token}}',
    url: '{{api.url}}/health',
    vars: 'Your own {{variables}}, like a token',
    braces: '{{ }}',
    value: 'Value (may use {{variables}})',
  };

  readonly s = computed(() => {
    const d = this.doc();
    // untracked: filling a new state writes its signals, which a computed may not do on its own.
    if (!(d.state instanceof RequestState)) d.state = untracked(() => new RequestState(d.draft as RequestDraft | undefined));
    return d.state as RequestState;
  });
  /**
   * {{api.url}} and friends: one click puts a service's address in the URL. Databases don't speak HTTP; your own
   * processes come before containers (an API is more likely yours than a mail catcher).
   */
  readonly services = computed(() => {
    const databases = new Set(this.catalog.databases().map((d) => d.service));
    const kind = new Map((this.engine.project()?.services ?? []).map((s) => [s.name, s.kind]));
    return this.requests
      .variables()
      .filter((v) => v.source === 'service' && v.name.endsWith('.url') && !databases.has(v.name.slice(0, -4)))
      .map((v) => ({ name: v.name.slice(0, -4), variable: `{{${v.name}}}`, value: v.value }))
      .sort((a, b) => (kind.get(a.name) === 'process' ? 0 : 1) - (kind.get(b.name) === 'process' ? 0 : 1));
  });
  readonly collections = computed(() => [...new Set(this.requests.saved().map((r) => r.collection).filter(Boolean))]);
  readonly status = computed(() => {
    const r = this.s().result();
    if (!r || r.outcome !== 'answered') return '';
    return r.status < 300 ? 'ok' : r.status < 400 ? 'redirect' : r.status < 500 ? 'client' : 'server';
  });
  readonly prettyBody = computed(() => {
    const r = this.s().result();
    if (!r || r.outcome !== 'answered') return '';
    if (!this.s().pretty()) return r.body;
    const type = r.headers.find((h) => h.name.toLowerCase() === 'content-type')?.value ?? '';
    const t = r.body.trim();
    if (type.includes('json') || t.startsWith('{') || t.startsWith('[')) {
      try {
        return JSON.stringify(JSON.parse(t), null, 2);
      } catch {
        return r.body;
      }
    }
    return r.body;
  });

  /** ws:// and wss:// addresses open a WebSocket instead of sending one request. */
  readonly isSocket = computed(() => /^wss?:\/\//i.test(this.s().url().trim()));

  constructor() {
    // A socket's events are polled while its tab is shown; closing the tab closes the connection.
    const timer = setInterval(() => this.pollSocket(), 500);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      const id = this.s().socketId();
      if (id && !this.wb.docs().some((d) => d.id === this.doc().id)) this.engine.closeSocket(id);
    });
    // A new request starts at the first service's address, so the port never has to be typed.
    effect(() => {
      const s = this.s();
      const first = this.services()[0];
      if (!s.url() && !s.savedId() && first) untracked(() => s.url.set(first.variable + '/'));
    });
    // The tab shows a dot when a saved request was changed and not saved again.
    effect(() => {
      const s = this.s();
      const dirty = !!s.savedId() && s.fingerprint() !== s.savedAs();
      untracked(() => this.doc().dirty.set(dirty));
    });
  }

  async send(): Promise<void> {
    const s = this.s();
    if (this.isSocket()) return this.toggleSocket();
    if (!s.url().trim() || s.sending()) return;
    s.sending.set(true);
    s.error.set(null);
    const r = await this.engine.httpSend(s.full());
    s.sending.set(false);
    if (!r.ok) {
      s.error.set(r.error);
      s.result.set(null);
    } else {
      s.result.set(r.value);
      if (r.value.service) this.logs.emit(r.value.service);
      if (r.value.outcome === 'failed' || r.value.checks.some((c) => !c.passed)) s.view.set('body');
    }
    this.requests.refresh(); // history, and variables a capture set
  }

  /** A mock route from this request and its answer: same method and path, same status and body. */
  async mockThis(): Promise<void> {
    const s = this.s();
    const r = s.result();
    if (!r || r.outcome !== 'answered') return;
    const path = s.url().replace(/^(\{\{[^}]+\}\}|https?:\/\/[^/]+)/, '').split('?')[0].replace(/\{\{\s*([\w.-]+)\s*\}\}/g, ':$1') || '/';
    // The same method and path again replaces that route's answer instead of adding a second one.
    const known = await this.engine.mock(999_999_999_999);
    const id = known.ok ? (known.value.routes.find((x) => x.method === s.method() && x.path === path)?.id ?? '') : '';
    const saved = await this.engine.saveMockRoute({ id, method: s.method(), path, status: r.status, headers: [], body: r.body, delayMs: 0 });
    if (!saved.ok) return this.toast.emit(saved.error);
    const route = saved.value.routes.find((x) => x.method === s.method() && x.path === path);
    this.show.emit(this.wb.open({ kind: 'mock', open: route?.id }));
  }

  // ---- SQL the request ran ----

  /** Postgres databases of the project whose query logging is off: the SQL tab offers to switch it on. */
  readonly loggingOff = signal<string[]>([]);

  async checkLogging(): Promise<void> {
    const off: string[] = [];
    for (const d of this.catalog.databases().filter((x) => x.kind === 'postgres')) {
      const r = await this.engine.queryLogging(d.service);
      if (r.ok && r.value.supported && !r.value.on) off.push(d.service);
    }
    this.loggingOff.set(off);
  }

  async turnOnLogging(service: string): Promise<void> {
    const r = await this.engine.setQueryLogging(service, true);
    if (!r.ok) return this.toast.emit(r.error);
    this.toast.emit(`${service} now logs every query: send the request again to see its SQL`);
    await this.checkLogging();
  }

  openRow(row: NonNullable<HttpResultView['rows']>[number]): void {
    this.show.emit(this.wb.open({ kind: 'table', service: row.service, schema: row.schema, table: row.table, filter: { column: row.column, value: row.value } }));
  }

  /** Whether this query is one of the request's repeated ones (shown in the warning colour). */
  repeated(r: { repeats?: { shape: string }[] }, shape: string): boolean {
    return (r.repeats ?? []).some((x) => x.shape === shape);
  }

  /** Opens the database's slowest-queries list with this query pointed at: is it slow every time, or just now? */
  openSlow(service: string, shape: string): void {
    this.show.emit(this.wb.open({ kind: 'slow', service, highlight: shape }));
  }

  // ---- WebSocket ----

  async toggleSocket(): Promise<void> {
    const s = this.s();
    const id = s.socketId();
    if (id && s.socketState() !== 'closed') {
      await this.engine.closeSocket(id);
      s.socketState.set('closed');
      s.socketEvents.update((l) => [...l, { seq: -1, at: new Date().toISOString(), direction: 'info', text: 'disconnected' }]);
      return;
    }
    s.error.set(null);
    s.socketEvents.set([]);
    const r = await this.engine.openSocket(s.call());
    if (!r.ok) {
      s.error.set(r.error);
      return;
    }
    s.socketId.set(r.value.id);
    s.socketState.set('connecting');
  }

  private async pollSocket(): Promise<void> {
    const s = this.s();
    const id = s.socketId();
    if (!id || s.socketState() === 'closed') return;
    const events = s.socketEvents();
    const after = events.length ? Math.max(0, ...events.map((e) => e.seq)) : 0;
    const r = await this.engine.pollSocket(id, after);
    if (!r.ok) {
      s.socketState.set('closed');
      return;
    }
    s.socketState.set(r.value.state);
    if (r.value.events.length) s.socketEvents.update((l) => [...l, ...r.value.events].slice(-1000));
  }

  /** Local time with milliseconds: messages often come close together. */
  clock(iso: string): string {
    const d = new Date(iso);
    return d.toLocaleTimeString('en-GB', { hour12: false }) + '.' + String(d.getMilliseconds()).padStart(3, '0');
  }

  async sendMessage(): Promise<void> {
    const s = this.s();
    const id = s.socketId();
    const text = s.outgoing();
    if (!id || !text) return;
    const r = await this.engine.sendSocket(id, text);
    if (r.ok) s.outgoing.set('');
    else this.toast.emit(r.error);
  }

  async save(): Promise<void> {
    const s = this.s();
    if (!s.name().trim()) {
      s.name.set(this.suggestName());
    }
    s.saving.set(true);
    const r = await this.engine.httpSave({ ...s.full(), id: s.savedId() ?? undefined, name: s.name(), collection: s.collection() });
    s.saving.set(false);
    if (!r.ok) {
      s.error.set(r.error);
      return;
    }
    this.requests.set(r.value);
    s.savedId.set(r.value.id);
    s.savedAs.set(s.fingerprint());
    const d = this.doc();
    if (d.spec.kind === 'request') d.spec = { kind: 'request', requestId: r.value.id };
    this.wb.rename(d.id, s.name());
    this.toast.emit(`Saved "${s.name()}"${s.collection() ? ' in ' + s.collection() : ''}`);
  }

  /** "GET /orders/7" → "GET orders 7": a name to start from. */
  private suggestName(): string {
    const path = this.s().url().replace(/\{\{[^}]+\}\}/g, '').replace(/^https?:\/\/[^/]+/, '');
    return `${this.s().method()} ${path || '/'}`.trim();
  }

  useService(variable: string): void {
    const s = this.s();
    s.url.set(s.url().replace(/^(\{\{[^}]+\}\}|https?:\/\/[^/]+)?/, variable));
  }

  // ---- query params: the URL stays the truth; the table reads it and writes it back ----

  readonly params = computed(() => {
    const url = this.s().url();
    const q = url.indexOf('?');
    if (q < 0) return [];
    return url
      .slice(q + 1)
      .split('&')
      .filter((p) => p !== '')
      .map((p) => {
        const eq = p.indexOf('=');
        return { name: decode(eq < 0 ? p : p.slice(0, eq)), value: eq < 0 ? '' : decode(p.slice(eq + 1)) };
      });
  });

  setParams(list: { name: string; value: string }[]): void {
    const s = this.s();
    const base = s.url().split('?')[0];
    const query = list.map((p) => (p.value === '' && !p.name ? '' : `${encode(p.name)}=${encode(p.value)}`)).filter(Boolean);
    s.url.set(query.length ? `${base}?${query.join('&')}` : base);
  }

  setParam(i: number, field: 'name' | 'value', value: string): void {
    this.setParams(this.params().map((p, j) => (j === i ? { ...p, [field]: value } : p)));
  }

  addParam(): void {
    this.setParams([...this.params(), { name: 'name', value: '' }]);
  }

  removeParam(i: number): void {
    this.setParams(this.params().filter((_, j) => j !== i));
  }

  addHeader(): void {
    this.s().headers.update((h) => [...h, { name: '', value: '', on: true }]);
  }

  setHeader(i: number, field: 'name' | 'value' | 'on', value: string | boolean): void {
    this.s().headers.update((h) => h.map((x, j) => (j === i ? { ...x, [field]: value } : x)));
  }

  removeHeader(i: number): void {
    this.s().headers.update((h) => h.filter((_, j) => j !== i));
  }

  // ---- body: text, form fields or files ----

  setBodyType(t: BodyType): void {
    const s = this.s();
    s.bodyType.set(t);
    // GraphQL is always a POST to one endpoint, usually /graphql.
    if (t === 'graphql') {
      s.method.set('POST');
      if (!/\/graphql/.test(s.url())) s.url.set(s.url().replace(/\/?$/, '/graphql'));
    }
    if ((t === 'form' || t === 'multipart') && s.form().length === 0) s.form.set([{ name: '', value: '', file: false, on: true }]);
  }

  /** Asks the endpoint for its schema: the queries and mutations it offers. */
  async loadSchema(): Promise<void> {
    const s = this.s();
    const r = await this.engine.graphQlSchema(s.call());
    if (!r.ok) {
      s.error.set(r.error);
      return;
    }
    s.error.set(null);
    s.operations.set(r.value.operations);
  }

  useOperation(op: GraphQlOperation): void {
    const s = this.s();
    s.body.set(op.example);
    if (op.args.length && !s.variables().trim()) s.variables.set('{\n' + op.args.map((a) => `  "${a.split(':')[0]}": null`).join(',\n') + '\n}');
  }

  addField(): void {
    this.s().form.update((f) => [...f, { name: '', value: '', file: false, on: true }]);
  }

  setField(i: number, field: 'name' | 'value' | 'file' | 'on', value: string | boolean): void {
    this.s().form.update((f) => f.map((x, j) => (j === i ? { ...x, [field]: value } : x)));
  }

  removeField(i: number): void {
    this.s().form.update((f) => f.filter((_, j) => j !== i));
  }

  /** A file to upload: its path, which the engine reads when sending. */
  async pickFile(i: number): Promise<void> {
    if (!('__TAURI_INTERNALS__' in window)) {
      this.toast.emit('Type the file path: the engine reads the file from disk when sending');
      return;
    }
    const { open } = await import('@tauri-apps/plugin-dialog');
    const path = await open({ multiple: false });
    if (typeof path === 'string') {
      this.setField(i, 'value', path);
      this.setField(i, 'file', true);
    }
  }

  // ---- auth ----

  setAuth(type: AuthView['type']): void {
    const a = this.s().auth();
    this.s().auth.set(
      type === 'bearer'
        ? { type, token: a.type === 'bearer' ? a.token : '{{token}}' }
        : type === 'basic'
          ? { type, user: a.type === 'basic' ? a.user : '', password: a.type === 'basic' ? a.password : '' }
          : { type: 'none' },
    );
  }

  setAuthField(field: 'token' | 'user' | 'password', value: string): void {
    this.s().auth.update((a) => ({ ...a, [field]: value }) as AuthView);
  }

  // ---- checks and captures ----

  readonly parts: { part: RulePart; label: string; path: boolean }[] = [
    { part: 'status', label: 'status', path: false },
    { part: 'json', label: 'json', path: true },
    { part: 'header', label: 'header', path: true },
    { part: 'body', label: 'body', path: false },
    { part: 'time', label: 'time (ms)', path: false },
  ];
  readonly ops: { op: CheckView['op']; label: string }[] = [
    { op: 'equals', label: '=' },
    { op: 'not_equals', label: '≠' },
    { op: 'contains', label: 'contains' },
    { op: 'exists', label: 'exists' },
    { op: 'less', label: '<' },
    { op: 'greater', label: '>' },
  ];
  hasPath = (part: RulePart) => this.parts.find((p) => p.part === part)?.path ?? false;

  addCheck(): void {
    this.s().checks.update((c) => [...c, c.length === 0 ? { part: 'status', path: '', op: 'equals', expected: '200' } : { part: 'json', path: '', op: 'exists', expected: '' }]);
  }

  setCheck(i: number, field: keyof CheckView, value: string): void {
    this.s().checks.update((c) => c.map((x, j) => (j === i ? { ...x, [field]: value } : x)));
  }

  removeCheck(i: number): void {
    this.s().checks.update((c) => c.filter((_, j) => j !== i));
  }

  addCapture(): void {
    this.s().captures.update((c) => [...c, { variable: c.length ? '' : 'token', part: 'json', path: c.length ? '' : 'access_token' }]);
  }

  setCapture(i: number, field: keyof CaptureView, value: string): void {
    this.s().captures.update((c) => c.map((x, j) => (j === i ? { ...x, [field]: value } : x)));
  }

  removeCapture(i: number): void {
    this.s().captures.update((c) => c.filter((_, j) => j !== i));
  }

  readonly failedChecks = computed(() => this.s().result()?.checks.filter((c) => !c.passed).length ?? 0);

  /** "Copy as curl": the engine fills in the variables, so it runs as is in a terminal. */
  async copyCurl(): Promise<void> {
    const r = await this.engine.httpCurl(this.s().call());
    if (!r.ok) {
      this.s().error.set(r.error);
      return;
    }
    navigator.clipboard?.writeText(r.value.curl);
    this.toast.emit(r.value.missing.length ? `Copied, but {{${r.value.missing.join('}}, {{')}}} has no value yet` : 'Copied as curl (variables filled in)');
  }

  /** Sets Content-Type: application/json if no Content-Type is there yet. */
  jsonBody(): void {
    const s = this.s();
    if (!s.headers().some((h) => h.name.toLowerCase() === 'content-type')) {
      s.headers.update((h) => [...h, { name: 'Content-Type', value: 'application/json', on: true }]);
    }
    try {
      s.body.set(JSON.stringify(JSON.parse(s.body()), null, 2));
    } catch {
      /* not JSON yet: leave as typed */
    }
  }

  key(e: KeyboardEvent): void {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      this.send();
    } else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
      e.preventDefault();
      this.save();
    }
  }

  size(n: number): string {
    return n < 1024 ? `${n} B` : n < 1024 * 1024 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`;
  }

  copy(text: string): void {
    navigator.clipboard?.writeText(text);
    this.toast.emit('Copied');
  }
}

/** Encodes a query part but leaves {{variables}} as typed, so the engine can still fill them in. */
function encode(text: string): string {
  return text
    .split(/(\{\{[^}]*\}\})/)
    .map((part, i) => (i % 2 ? part : encodeURIComponent(part)))
    .join('');
}

function decode(text: string): string {
  try {
    return decodeURIComponent(text.replace(/\+/g, ' '));
  } catch {
    return text; // a lone % typed by hand: show it as is
  }
}
