import { Component, computed, inject, output, signal } from '@angular/core';
import { Engine, HttpOverview, SavedRequestView } from './engine';
import { Requests } from './requests';
import { Catalog } from './catalog';
import { Doc, Workbench } from './workbench';
import { DocTabs } from './doc-tabs';
import { RequestState } from './request-view';
import { Panes } from './panes';
import { openFile } from './files';
import { Icon } from './icon';

type Variable = HttpOverview['variables'][number];

/**
 * The Workbench's API page (Postman-like): saved requests by collection, recent history and variables on the left;
 * open requests as tabs on the right. The engine sends the requests and keeps the files.
 */
@Component({
  selector: 'dd-api',
  imports: [Icon, DocTabs, Panes],
  template: `
    <aside class="side">
      <div class="top">
        <button class="primary sm new" (click)="newRequest()"><dd-icon name="plus" [size]="12" /> New request</button>
        <span class="importmenu">
          <button class="sm" (click)="importOpen.set(!importOpen())" title="Requests from OpenAPI (Swagger), Postman or curl"><dd-icon name="download" [size]="12" /> Import</button>
          @if (importOpen()) {
            <div class="menu">
              <div class="mlabel">OpenAPI from a running service</div>
              @for (v of httpServices(); track v) {
                <button (click)="importFromService(v)"><dd-icon name="services" [size]="12" /> {{ v }}</button>
              } @empty { <div class="dim small pad">No service with a port.</div> }
              <div class="mlabel">From a file</div>
              <button (click)="importFile('openapi')">OpenAPI / Swagger JSON…</button>
              <button (click)="importFile('postman')">Postman collection…</button>
              <div class="mlabel">From the clipboard</div>
              <button (click)="importOpen.set(false); curlOpen.set(true)">Paste a curl command…</button>
            </div>
          }
        </span>
      </div>
      <input class="find" placeholder="Find a request…" [value]="find()" (input)="find.set($any($event.target).value)" />

      <div class="label">Collections</div>
      @for (g of groups(); track g.name) {
        <button class="coll" (click)="toggle(g.name)">
          <dd-icon [name]="closed().has(g.name) ? 'chevronRight' : 'chevronDown'" [size]="11" />
          <dd-icon name="folder" [size]="13" />
          <span class="grow-text">{{ g.name || 'No collection' }}</span>
          @if (requests.shared().includes(g.name)) { <span class="repo mono" title="Saved in the project folder (.devdesk/requests/): commit it so teammates get it">repo</span> }
          <span class="dim mono small">{{ g.items.length }}</span>
          @if (g.name) {
            <span class="run share" role="button" [title]="requests.shared().includes(g.name) ? 'Keep this collection on this machine only' : 'Share with the team: save it in the project folder (.devdesk/requests/) for git'" (click)="$event.stopPropagation(); share(g.name, !requests.shared().includes(g.name))"><dd-icon [name]="requests.shared().includes(g.name) ? 'lock' : 'external'" [size]="10" /></span>
          }
          <span class="run" role="button" title="Run every request of this collection, in order, with their checks" (click)="$event.stopPropagation(); run(g.name)"><dd-icon name="play" [size]="10" /></span>
        </button>
        @if (!closed().has(g.name)) {
          @for (r of g.items; track r.id) {
            <div class="item" [class.on]="isOpen(r.id)" (click)="openSaved(r)" [title]="r.method + ' ' + r.url">
              <span class="m mono m-{{ r.method }}">{{ r.method }}</span>
              <span class="grow-text">{{ r.name }}</span>
              <button class="ghost icon sm del" title="Delete this saved request" (click)="$event.stopPropagation(); remove(r)"><dd-icon name="trash" [size]="11" /></button>
            </div>
          }
        }
      } @empty {
        <div class="dim small pad">Nothing saved yet. Name a request and press Save.</div>
      }

      <div class="label">History</div>
      <div class="history">
        @for (h of history(); track $index) {
          <div class="item" (click)="openHistory(h)" [title]="h.at + ' · ' + h.url">
            <span class="m mono m-{{ h.method }}">{{ h.method }}</span>
            <span class="grow-text mono small">{{ shortUrl(h.url) }}</span>
            <span class="st mono" [class.bad]="h.status === null || h.status >= 400">{{ h.status ?? '✕' }}</span>
          </div>
        } @empty {
          <div class="dim small pad">Requests you send show up here.</div>
        }
      </div>
      <div class="bottom">
        @if (requests.cookies().length) {
          <div class="label">Cookies <span class="dim">(sent back like a browser does)</span></div>
          @for (c of requests.cookies(); track c.domain + c.name) {
            <div class="cookie mono small" [title]="c.domain + c.path + (c.httpOnly ? ' · HttpOnly' : '')"><span>{{ c.name }}</span><span class="dim">{{ c.value }}</span></div>
          }
          <button class="ghost sm" (click)="clearCookies()" title="Forget every cookie: the next request starts logged out">Clear cookies</button>
        }
        <button class="ghost sm vars" (click)="show.emit(wb.open({ kind: 'mock' }))" title="Fake endpoints on a port of this machine"><dd-icon name="box" [size]="12" /> Mock server</button>
        <button class="ghost sm vars" (click)="show.emit(wb.open({ kind: 'stress' }))" title="Heavy fake traffic on this machine: which service gives up first"><dd-icon name="pulse" [size]="12" /> Stress lab</button>
        <button class="ghost sm vars" (click)="show.emit(wb.open({ kind: 'recorder' }))" title="Record the real calls to a service, then replay them"><dd-icon name="radio" [size]="12" /> Traffic recorder</button>
        <button class="ghost sm vars" (click)="openVars()">{{ braces }} Variables <span class="dim mono">{{ requests.variables().length }}</span></button>
      </div>
    </aside>

    <section class="main">
      <dd-doc-tabs (show)="show.emit($event)" />
      <dd-panes [left]="current()" (toast)="toast.emit($event)" (show)="show.emit($event)" (variables)="openVars()" (logs)="logs.emit($event)">
        <div class="none">
          <dd-icon name="send" [size]="28" />
          <p>Build a request to one of the project's services, or open a saved one on the left.</p>
          <button class="primary" (click)="newRequest()"><dd-icon name="plus" [size]="13" /> New request</button>
          <p class="dim"><kbd>Ctrl Enter</kbd> sends · <kbd>Ctrl S</kbd> saves · <kbd>Ctrl K</kbd> finds saved requests</p>
        </div>
      </dd-panes>
    </section>

    @if (curlOpen()) {
      <div class="scrim" (click)="curlOpen.set(false)">
        <div class="modal" (click)="$event.stopPropagation()" role="dialog" aria-label="Paste curl">
          <h3>Paste a curl command</h3>
          <p class="dim">From your browser's dev tools (Network, right click, Copy as cURL) or from docs. It opens as a new request.</p>
          <textarea #curl class="mono curlbox" spellcheck="false" placeholder="curl 'http://localhost:3001/api/orders' -H 'accept: application/json'"></textarea>
          @if (curlError(); as e) { <div class="err small">{{ e }}</div> }
          <div class="modal-actions">
            <button class="ghost" (click)="curlOpen.set(false)">Cancel</button>
            <button class="primary" (click)="fromCurl(curl.value)">Open as request</button>
          </div>
        </div>
      </div>
    }
    @if (varsOpen()) {
      <div class="scrim" (click)="varsOpen.set(false)">
        <div class="modal" (click)="$event.stopPropagation()" role="dialog" aria-label="Variables">
          <h3>Variables</h3>
          <p class="dim">Write <span class="mono">{{ example }}</span> in a URL, header or body. The engine fills it in when sending; the history keeps the placeholder, not the value.</p>
          <div class="vlabel">From the project's services</div>
          @for (v of serviceVars(); track v.name) {
            <div class="vrow ro mono"><span>{{ v.name }}</span><span class="dim">{{ v.value }}</span></div>
          } @empty { <div class="dim small">No service declares a port.</div> }
          <div class="vlabel">Yours <span class="dim">(override the ones above)</span></div>
          @for (v of draft(); track $index; let i = $index) {
            <div class="vrow">
              <input class="mono" placeholder="name" [value]="v.name" (input)="edit(i, 'name', $any($event.target).value)" />
              <input class="mono" [type]="v.secret && !shown().has(i) ? 'password' : 'text'" placeholder="value" [value]="v.value" (input)="edit(i, 'value', $any($event.target).value)" />
              @if (v.secret) {
                <button class="ghost icon sm" [title]="shown().has(i) ? 'Hide' : 'Show'" (click)="reveal(i)"><dd-icon [name]="shown().has(i) ? 'eyeOff' : 'eye'" [size]="13" /></button>
              } @else { <span></span> }
              <button class="ghost icon sm" title="Remove" (click)="drop(i)"><dd-icon name="close" [size]="12" /></button>
            </div>
          }
          <button class="ghost sm" (click)="add()"><dd-icon name="plus" [size]="12" /> Variable</button>
          @if (varsError(); as e) { <div class="err small">{{ e }}</div> }
          <div class="modal-actions">
            <button class="ghost" (click)="varsOpen.set(false)">Cancel</button>
            <button class="primary" (click)="saveVars()">Save variables</button>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    :host { display: grid; grid-template-columns: 260px 1fr; height: 100%; min-height: 0; }
    .side { border-right: 1px solid var(--border); background: var(--bg-1); padding: 10px 8px; overflow: auto; display: flex; flex-direction: column; gap: 2px; }
    .top { display: flex; gap: 6px; margin: 0 2px 6px; }
    .new { flex: 1; }
    .importmenu { position: relative; }
    .repo { font-size: 9px; color: var(--accent); border: 1px solid var(--accent-line); border-radius: 3px; padding: 0 4px; }
    .menu { position: absolute; left: 0; top: 30px; z-index: 10; display: flex; flex-direction: column; background: var(--bg-2); border: 1px solid var(--border-strong); border-radius: var(--radius); padding: 4px; min-width: 240px; box-shadow: var(--shadow-float); }
    .menu button { justify-content: flex-start; background: transparent; border-color: transparent; height: 27px; gap: 6px; }
    .menu button:hover { background: var(--bg-1); }
    .mlabel { font: 500 10px var(--font-mono); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; padding: 8px 8px 2px; }
    .run { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; border-radius: 4px; color: var(--accent); opacity: 0; }
    .coll:hover .run { opacity: 1; }
    .run:hover { background: var(--accent-deep); }
    .bottom { margin-top: auto; display: flex; flex-direction: column; gap: 2px; }
    .cookie { display: flex; gap: 8px; padding: 1px 8px; }
    .cookie span:last-child { overflow: hidden; text-overflow: ellipsis; }
    .curlbox { min-height: 140px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius); padding: 10px; font-size: var(--fs-sm); color: var(--text-1); resize: vertical; }
    .find { margin: 0 2px 4px; height: 28px; }
    .label { font: 500 var(--fs-xs) var(--font-mono); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.08em; padding: 12px 8px 4px; }
    .coll { justify-content: flex-start; gap: 6px; height: 26px; background: transparent; border-color: transparent; padding: 0 6px; color: var(--text-1); }
    .coll:hover:not(:disabled) { background: var(--bg-2); border-color: transparent; }
    .item { display: flex; align-items: center; gap: 8px; height: 27px; padding: 0 4px 0 22px; border-radius: var(--radius); color: var(--text-2); cursor: pointer; font-size: var(--fs-sm); }
    .history .item { padding-left: 8px; }
    .item:hover { background: var(--bg-2); color: var(--text-1); }
    .item.on { background: var(--accent-weak); color: var(--text-1); }
    .m { font-size: 9px; font-weight: 600; width: 42px; flex: none; }
    .m-GET { color: var(--accent); } .m-POST { color: var(--warn); } .m-PUT, .m-PATCH { color: #7ec4f0; } .m-DELETE { color: var(--err); }
    .grow-text { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-align: left; }
    .del { opacity: 0; width: 20px; height: 20px; }
    .item:hover .del { opacity: 1; }
    .st { font-size: var(--fs-xs); color: var(--ok); }
    .st.bad { color: var(--err); }
    .history { display: flex; flex-direction: column; gap: 1px; }
    .vars { align-self: flex-start; }
    .dim { color: var(--text-3); }
    .small { font-size: var(--fs-xs); }
    .pad { padding: 4px 8px; }
    .main { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
    .main > :last-child { flex: 1; min-height: 0; }
    .none { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; color: var(--text-2); text-align: center; padding: 32px; }
    .none dd-icon { color: var(--accent); }
    .none p { margin: 0; }
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); display: flex; align-items: center; justify-content: center; z-index: 30; animation: fade 140ms ease-out; }
    .modal { width: min(640px, 92vw); max-height: 84vh; overflow: auto; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); padding: 22px; display: flex; flex-direction: column; gap: 6px; animation: rise 160ms ease-out; }
    .modal h3 { margin: 0; font-size: var(--fs-xl); }
    .modal p { margin: 0 0 6px; font-size: var(--fs-sm); }
    .vlabel { font: 500 var(--fs-xs) var(--font-mono); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin-top: 10px; }
    .vrow { display: grid; grid-template-columns: 1fr 1.6fr 26px 26px; gap: 6px; align-items: center; }
    .vrow.ro { grid-template-columns: 1fr 1.6fr; font-size: var(--fs-sm); padding: 3px 0; }
    .vrow input { height: 28px; font-size: var(--fs-sm); }
    .err { color: var(--err); }
    .modal-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }
  `,
})
export class ApiPanel {
  readonly engine = inject(Engine);
  readonly requests = inject(Requests);
  readonly wb = inject(Workbench);
  readonly catalog = inject(Catalog);
  readonly show = output<Doc>();
  readonly toast = output<string>();
  /** Show this service's logs (the one a request just went to). */
  readonly logs = output<string>();
  readonly find = signal('');
  readonly closed = signal<Set<string>>(new Set());
  readonly varsOpen = signal(false);
  readonly draft = signal<Variable[]>([]);
  readonly shown = signal<Set<number>>(new Set());
  readonly varsError = signal<string | null>(null);
  readonly importOpen = signal(false);
  readonly curlOpen = signal(false);
  readonly curlError = signal<string | null>(null);
  readonly braces = '{{ }}';
  readonly example = '{{token}}';

  readonly current = computed(() => {
    void this.wb.activeId();
    return this.wb.lastOn('api');
  });
  readonly serviceVars = computed(() => this.requests.variables().filter((v) => v.source === 'service'));
  readonly groups = computed(() => {
    const f = this.find().toLowerCase();
    const by = new Map<string, SavedRequestView[]>();
    for (const r of this.requests.saved()) {
      if (f && !`${r.name} ${r.url}`.toLowerCase().includes(f)) continue;
      by.set(r.collection, [...(by.get(r.collection) ?? []), r]);
    }
    return [...by.entries()].sort(([a], [b]) => (a === '' ? 1 : b === '' ? -1 : a.localeCompare(b))).map(([name, items]) => ({ name, items }));
  });
  readonly history = computed(() => {
    const f = this.find().toLowerCase();
    return this.requests.history().filter((h) => !f || h.url.toLowerCase().includes(f)).slice(0, 40);
  });

  constructor() {
    this.requests.refresh();
    this.catalog.refresh(); // which services are databases (no HTTP chips for them)
  }

  /** Services that may speak HTTP: every service with an address, minus the databases. */
  readonly httpServices = computed(() => {
    const dbs = new Set(this.catalog.databases().map((d) => d.service));
    return this.requests
      .variables()
      .filter((v) => v.source === 'service' && v.name.endsWith('.url'))
      .map((v) => v.name.slice(0, -4))
      .filter((n) => !dbs.has(n));
  });

  async importFromService(service: string): Promise<void> {
    this.importOpen.set(false);
    const r = await this.engine.httpImportOpenApi({ service });
    this.imported(r);
  }

  async importFile(kind: 'openapi' | 'postman'): Promise<void> {
    this.importOpen.set(false);
    const f = await openFile(['json']);
    if (!f) return;
    // In the app the dialog gives a path and the engine reads the file (the webview can't); in a browser, the text.
    const from = 'content' in f ? { content: f.content } : { path: f.path };
    this.imported(kind === 'openapi' ? await this.engine.httpImportOpenApi(from) : await this.engine.httpImportPostman(from));
  }

  private imported(r: Awaited<ReturnType<Engine['httpImportPostman']>>): void {
    if (!r.ok) {
      this.toast.emit(r.error);
      return;
    }
    this.requests.set(r.value);
    this.toast.emit(`Imported ${r.value.imported} requests (${r.value.from})`);
  }

  async fromCurl(command: string): Promise<void> {
    const r = await this.engine.httpFromCurl(command);
    if (!r.ok) {
      this.curlError.set(r.error);
      return;
    }
    this.curlOpen.set(false);
    this.curlError.set(null);
    const doc = this.wb.open({ kind: 'request', requestId: null });
    doc.state = new RequestState(r.value);
    this.show.emit(doc);
  }

  async share(collection: string, shared: boolean): Promise<void> {
    const r = await this.engine.httpShare(collection, shared);
    if (!r.ok) return this.toast.emit(r.error);
    this.requests.set(r.value);
    this.toast.emit(shared ? `"${collection}" is now in .devdesk/requests/: commit it to share it` : `"${collection}" is kept on this machine only`);
  }

  run(collection: string): void {
    this.show.emit(this.wb.open({ kind: 'run', collection }));
  }

  async clearCookies(): Promise<void> {
    const r = await this.engine.httpClearCookies();
    if (r.ok) this.requests.set(r.value);
  }

  /** The dialog starts from what the engine has. */
  openVars(): void {
    this.draft.set(this.requests.variables().filter((x) => x.source === 'user').map((x) => ({ ...x })));
    this.shown.set(new Set());
    this.varsError.set(null);
    this.varsOpen.set(true);
  }

  newRequest(): void {
    this.show.emit(this.wb.open({ kind: 'request', requestId: null }));
  }

  openSaved(r: SavedRequestView): void {
    const doc = this.wb.open({ kind: 'request', requestId: r.id });
    if (!(doc.state instanceof RequestState)) {
      doc.state = new RequestState(r);
      this.wb.rename(doc.id, r.name);
    }
    this.show.emit(doc);
  }

  /** A history entry opens as a new, unsaved request with the same method, URL, headers and body. */
  openHistory(h: HttpOverview['history'][number]): void {
    const doc = this.wb.open({ kind: 'request', requestId: null });
    doc.state = new RequestState(h);
    this.show.emit(doc);
  }

  async remove(r: SavedRequestView): Promise<void> {
    if (!confirm(`Delete the saved request "${r.name}"?`)) return;
    const res = await this.engine.httpDelete(r.id);
    if (res.ok) {
      this.requests.set(res.value);
      this.toast.emit(`Deleted "${r.name}"`);
    }
  }

  isOpen(id: string): boolean {
    const s = this.current()?.spec;
    return s?.kind === 'request' && s.requestId === id;
  }

  toggle(name: string): void {
    this.closed.update((c) => {
      const next = new Set(c);
      next.has(name) ? next.delete(name) : next.add(name);
      return next;
    });
  }

  shortUrl(url: string): string {
    return url.replace(/^\{\{([^}]+)\.url\}\}/, '$1').replace(/^https?:\/\//, '');
  }

  // ---- variables dialog ----

  add(): void {
    this.draft.update((d) => [...d, { name: '', value: '', source: 'user', secret: false }]);
  }

  edit(i: number, field: 'name' | 'value', value: string): void {
    this.draft.update((d) => d.map((x, j) => (j === i ? { ...x, [field]: value } : x)));
  }

  drop(i: number): void {
    this.draft.update((d) => d.filter((_, j) => j !== i));
  }

  reveal(i: number): void {
    this.shown.update((s) => {
      const next = new Set(s);
      next.has(i) ? next.delete(i) : next.add(i);
      return next;
    });
  }

  async saveVars(): Promise<void> {
    const vars = Object.fromEntries(this.draft().filter((v) => v.name.trim()).map((v) => [v.name.trim(), v.value]));
    const r = await this.engine.httpVariables(vars);
    if (!r.ok) {
      this.varsError.set(r.error);
      return;
    }
    this.requests.set(r.value);
    this.varsOpen.set(false);
    this.toast.emit('Variables saved');
  }
}
