import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Engine, MockHit, MockRoute } from './engine';
import { Doc } from './workbench';
import { Icon } from './icon';

class MockState {
  readonly routes = signal<MockRoute[]>([]);
  readonly port = signal<number | null>(null);
  readonly wantedPort = signal(18900);
  readonly hits = signal<MockHit[]>([]);
  readonly openId = signal<string | null>(null);
  /** The route being edited, before Save. */
  readonly draft = signal<MockRoute | null>(null);
  readonly error = signal<string | null>(null);
}

/**
 * The mock server: fake endpoints DevDesk answers on a port of this machine, and every request they got. A frontend
 * can point at it before the real API exists.
 */
@Component({
  selector: 'dd-mock-view',
  imports: [Icon],
  template: `
    @let st = s();
    <div class="bar">
      <span class="label">Mock server</span>
      @if (st.port(); as p) {
        <span class="on mono">● http://localhost:{{ p }}</span>
        <button class="sm danger" (click)="stop()"><dd-icon name="stop" [size]="11" /> Stop</button>
      } @else {
        <span class="dim">port</span>
        <input class="mono port" type="number" [value]="st.wantedPort()" (input)="st.wantedPort.set(+$any($event.target).value)" />
        <button class="sm primary" (click)="start()"><dd-icon name="play" [size]="11" /> Start</button>
      }
      <span class="grow"></span>
      <button class="sm" (click)="add()"><dd-icon name="plus" [size]="12" /> Route</button>
    </div>
    @if (st.error(); as e) { <div class="error">{{ e }}</div> }
    <div class="body">
      <div class="routes">
        @for (r of st.routes(); track r.id) {
          <div class="route" [class.on]="st.openId() === r.id" (click)="edit(r)">
            <span class="m mono m-{{ r.method }}">{{ r.method }}</span>
            <span class="path mono">{{ r.path }}</span>
            <span class="st mono" [class.bad]="r.status >= 400">{{ r.status }}</span>
            @if (r.delayMs) { <span class="dim small mono">+{{ r.delayMs }} ms</span> }
          </div>
        } @empty {
          <div class="dim small pad">No routes yet. Add one, or press "Mock this" under a request's answer to copy it here.</div>
        }
      </div>
      <div class="editor">
        @if (st.draft(); as d) {
          <div class="row">
            <select class="mono" (change)="set('method', $any($event.target).value)">
              @for (m of methods; track m) { <option [value]="m" [selected]="m === d.method">{{ m }}</option> }
            </select>
            <input class="mono grow-in" [value]="d.path" (input)="set('path', $any($event.target).value)" placeholder="/orders/:id" title=":name matches one part of the path; /* at the end matches anything below" />
            <input class="mono num" type="number" [value]="d.status" (input)="set('status', +$any($event.target).value)" title="Status code" />
            <input class="mono num" type="number" [value]="d.delayMs" (input)="set('delayMs', +$any($event.target).value)" title="Wait this many milliseconds before answering" />
            <span class="dim small">ms</span>
          </div>
          <textarea class="mono" spellcheck="false" [value]="d.body" (input)="set('body', $any($event.target).value)" [placeholder]="bodyHint"></textarea>
          <p class="dim small">The body may use the path's parts: in /orders/:id, {{ idHint }} becomes the id asked for. JSON bodies are sent as application/json.</p>
          <div class="row end">
            @if (d.id) { <button class="ghost sm danger-text" (click)="remove(d.id)"><dd-icon name="trash" [size]="12" /> Delete</button> }
            <span class="grow"></span>
            <button class="ghost sm" (click)="st.draft.set(null); st.openId.set(null)">Cancel</button>
            <button class="primary sm" (click)="save()"><dd-icon name="save" [size]="12" /> Save route</button>
          </div>
        } @else {
          <div class="dim pad">Pick a route to edit it.</div>
        }
      </div>
    </div>
    <div class="hits">
      <div class="hhead"><span class="label">Requests received</span><span class="grow"></span><button class="ghost sm" (click)="st.hits.set([])">Clear</button></div>
      <div class="hlist mono">
        @for (h of hitsShown(); track h.seq) {
          <div class="hit" [title]="h.body"><span class="t">{{ clock(h.at) }}</span><span class="m">{{ h.method }}</span><span class="tg">{{ h.target }}</span><span [class.bad]="h.status >= 400">{{ h.status }}</span><span class="dim">{{ h.route || 'no route' }}</span></div>
        } @empty {
          <div class="dim pad">{{ st.port() ? 'Waiting for requests on port ' + st.port() + '…' : 'Start the server, then point your app at it.' }}</div>
        }
      </div>
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-bottom: 1px solid var(--border); }
    .label { font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; }
    .on { color: var(--ok); }
    .port { width: 90px; }
    .grow { flex: 1; }
    .error { margin: 8px 14px 0; padding: 8px 12px; background: var(--err-bg); border-radius: var(--radius); font-size: var(--fs-sm); }
    .body { flex: 1; display: grid; grid-template-columns: minmax(260px, 1fr) 1.3fr; min-height: 0; }
    .routes { overflow: auto; border-right: 1px solid var(--border); padding: 6px 0; }
    .route { display: flex; align-items: center; gap: 10px; padding: 5px 14px; cursor: pointer; }
    .route:hover { background: var(--bg-2); }
    .route.on { background: var(--accent-weak); }
    .m { width: 52px; font-size: var(--fs-xs); font-weight: 600; }
    .m-GET { color: var(--accent); } .m-POST { color: var(--warn); } .m-DELETE { color: var(--err); } .m-PUT, .m-PATCH { color: #7ec4f0; }
    .path { flex: 1; overflow: hidden; text-overflow: ellipsis; }
    .st { color: var(--ok); } .bad { color: var(--err); }
    .editor { padding: 10px 14px; display: flex; flex-direction: column; gap: 8px; overflow: auto; }
    .row { display: flex; gap: 6px; align-items: center; }
    .row.end { justify-content: flex-end; }
    .grow-in { flex: 1; }
    .num { width: 80px; }
    textarea { min-height: 140px; flex: 1; resize: vertical; font-size: var(--fs-sm); background: var(--bg-0); color: var(--text-1); border: 1px solid var(--border); border-radius: var(--radius); padding: 10px 12px; }
    .danger-text { color: var(--err); }
    .hits { height: 34%; min-height: 120px; border-top: 1px solid var(--border); display: flex; flex-direction: column; }
    .hhead { display: flex; align-items: center; padding: 4px 14px; }
    .hlist { flex: 1; overflow: auto; padding: 0 14px 8px; font-size: var(--fs-sm); }
    .hit { display: flex; gap: 12px; padding: 1px 0; }
    .hit .t { color: var(--text-3); }
    .hit .tg { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .pad { padding: 10px 14px; }
  `,
})
export class MockViewComponent {
  readonly engine = inject(Engine);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();
  readonly methods = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'ANY'];
  readonly idHint = '{{id}}';
  /** The route "Mock this" asked to show was opened once (the tab keeps its spec afterwards). */
  private opened = false;
  readonly bodyHint = '{"id": {{id}}, "status": "paid"}';

  readonly s = computed(() => {
    const d = this.doc();
    if (!(d.state instanceof MockState)) d.state = new MockState();
    return d.state as MockState;
  });
  /** Newest first. */
  readonly hitsShown = computed(() => [...this.s().hits()].reverse());

  constructor() {
    effect(() => {
      void this.doc();
      untracked(() => this.load());
    });
    // New requests show up as they come; the routes may also change from "Mock this" in a request tab.
    const timer = setInterval(() => this.load(), 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  async load(): Promise<void> {
    const st = this.s();
    const hits = st.hits();
    const r = await this.engine.mock(hits.length ? hits[hits.length - 1].seq : 0);
    if (!r.ok) return;
    st.routes.set(r.value.routes);
    st.port.set(r.value.port);
    if (r.value.hits.length) st.hits.update((l) => [...l, ...r.value.hits].slice(-300));
    const want = this.doc().spec as { open?: string };
    if (want.open && !this.opened) {
      this.opened = true;
      const route = r.value.routes.find((x) => x.id === want.open);
      if (route) this.edit(route);
    }
  }

  async start(): Promise<void> {
    const r = await this.engine.startMock(this.s().wantedPort());
    this.s().error.set(r.ok ? null : r.error);
    if (r.ok) this.s().port.set(r.value.port);
  }

  async stop(): Promise<void> {
    await this.engine.stopMock();
    this.s().port.set(null);
  }

  add(): void {
    this.s().openId.set(null);
    this.s().draft.set({ id: '', method: 'GET', path: '/', status: 200, headers: [], body: '{}', delayMs: 0 });
  }

  edit(r: MockRoute): void {
    this.s().openId.set(r.id);
    this.s().draft.set({ ...r });
  }

  set<K extends keyof MockRoute>(field: K, value: MockRoute[K]): void {
    this.s().draft.update((d) => (d ? { ...d, [field]: value } : d));
  }

  async save(): Promise<void> {
    const d = this.s().draft();
    if (!d) return;
    const r = await this.engine.saveMockRoute(d);
    if (!r.ok) {
      this.s().error.set(r.error);
      return;
    }
    this.s().error.set(null);
    this.s().routes.set(r.value.routes);
    this.s().draft.set(null);
    this.s().openId.set(null);
    this.toast.emit(`Saved ${d.method} ${d.path}`);
  }

  async remove(id: string): Promise<void> {
    const r = await this.engine.deleteMockRoute(id);
    if (r.ok) this.s().routes.set(r.value.routes);
    this.s().draft.set(null);
    this.s().openId.set(null);
  }

  clock(iso: string): string {
    return new Date(iso).toLocaleTimeString('en-GB', { hour12: false });
  }
}
