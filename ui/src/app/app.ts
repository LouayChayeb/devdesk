import { Component, HostListener, computed, effect, inject, signal, untracked } from '@angular/core';
import { ConfigProblem, Engine } from './engine';
import { StatusDot } from './status-dot';
import { ServicesTable } from './services-table';
import { LogsPanel } from './logs-panel';
import { DiagnosisPanel } from './diagnosis-panel';
import { TimelinePanel } from './timeline-panel';
import { ConnectionsPanel } from './connections-panel';
import { ServiceDrawer } from './service-drawer';
import { GraphPanel } from './graph-panel';
import { PortsPanel } from './ports-panel';
import { CommandPalette, PaletteCommand } from './command-palette';
import { Icon } from './icon';
import { DataPanel } from './data-panel';
import { ApiPanel } from './api-panel';
import { MailPanel } from './mail-panel';
import { Catalog } from './catalog';
import { Doc, DocSpec, NAV, PAGES, Page, Workbench, pageOf } from './workbench';
import { bytes, cpu } from './format';
import { RequestState } from './request-view';
import { DocView } from './doc-view';
import { Requests } from './requests';
import { SetupWizard } from './setup-wizard';
import { FreshSetup } from './fresh-setup';
import { EnvEditor } from './env-editor';
import { TasksBar } from './tasks-bar';
import { LogSearch } from './log-search';
import { ShellView } from './shell-view';
import { SnapshotsView } from './snapshots-view';
import { RemoteViewComponent } from './remote-view';
import { TestsPage } from './tests-page';
import { ProjectSwitch } from './project-switch';

const RECENT_KEY = 'devdesk.recent';
const UI_KEY = 'devdesk.ui';

@Component({
  selector: 'app-root',
  imports: [StatusDot, ServicesTable, LogsPanel, DiagnosisPanel, TimelinePanel, ConnectionsPanel, ServiceDrawer, GraphPanel, PortsPanel, CommandPalette, Icon, DataPanel, ApiPanel, MailPanel, DocView, SetupWizard, FreshSetup, EnvEditor, TasksBar, LogSearch, ProjectSwitch, ShellView, TestsPage, SnapshotsView, RemoteViewComponent],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  readonly engine = inject(Engine);
  readonly wb = inject(Workbench);
  private readonly requests = inject(Requests);
  readonly catalog = inject(Catalog);
  readonly nav = NAV;
  readonly page = signal<Page>(this.validPage(this.loadUi().page));
  readonly logsHeight = signal<number>(this.loadUi().logs ?? 240);
  /** Ctrl+J: the log panel at the bottom of every page, shown or hidden. */
  readonly logsOpen = signal<boolean>(this.loadUi().logsOpen ?? true);
  readonly toasts = signal<{ id: number; text: string; kind: 'info' | 'err'; why?: string }[]>([]);
  /** When each service last popped up a crash: a crash loop shows one pop-up per 30 s, not one per second. */
  private readonly lastCrashPopup = new Map<string, number>();
  private toastId = 0;
  readonly selected = signal<string | null>(null);
  readonly diagnosisFor = signal<string | null>(null);
  readonly drawerFor = signal<string | null>(null);
  readonly paletteOpen = signal(false);
  readonly projectMenu = signal(false);
  readonly portFilter = signal('');
  readonly trustOpen = signal(false);
  /** The folder the setup wizard is open for (no devdesk.yaml yet, or "Edit setup"). */
  readonly setupFor = signal<string | null>(null);
  /** The folder "Set up" (fresh clone: .env, packages, images, one-time jobs) is open for. */
  readonly freshFor = signal<string | null>(null);
  readonly envOpen = signal(false);
  readonly snapshotsOpen = signal(false);
  /** Deploy / remote dev box. */
  readonly remoteOpen = signal(false);
  readonly logSearch = signal(false);
  /** The service whose shell window is open: every key goes to the shell, none to the app's shortcuts. */
  readonly shellFor = signal<string | null>(null);
  /** Ctrl+O. The value is the project to ask about right away ("" = just the list). */
  readonly switching = signal<string | null>(null);
  /** The folder of the last failed open: its "Set up" button needs it. */
  private lastPath = '';
  readonly failedPath = () => this.lastPath;
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
  readonly runningCount = computed(
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
  readonly current = computed(() => PAGES.find((p) => p.id === this.page())!);
  readonly isWork = computed(() => this.current().group === 'Work');
  /** "shop › Work › Data › orders": where you are, top left. */
  readonly crumbs = computed(() => {
    const out = [this.current().group, this.current().label];
    void this.wb.docs(); // a renamed tab is the same object: depend on the list so the new title shows
    const doc = this.isWork() ? this.wb.lastOn(this.page()) : null;
    if (doc && 'service' in doc.spec) out.push(doc.spec.service);
    if (doc) out.push(doc.title);
    return out;
  });

  /** In a window opened by "pop out": the one tab it shows. The main window's tabs are never touched from here. */
  readonly popout: Doc | null = this.takePopout();

  private takePopout(): Doc | null {
    const key = new URLSearchParams(location.search).get('popout');
    return key ? this.wb.takeOver(key) : null;
  }

  constructor() {
    this.engine.start();
    effect(() => {
      const c = this.engine.crash();
      if (c) untracked(() => this.popUpCrash(c));
    });
    if (this.popout) {
      document.title = `${this.popout.title} · DevDesk`;
      // What the pages would have loaded in the main window: which services are databases, saved requests.
      let loadedFor: string | undefined;
      effect(() => {
        const root = this.engine.project()?.root;
        if (root && root !== loadedFor) {
          loadedFor = root;
          untracked(() => {
            this.catalog.refresh();
            this.requests.refresh();
          });
        }
      });
      return;
    }
    // Another project: the open tables and keys were the old project's.
    // Each project keeps its own open tabs: written down as they change, reopened with the project.
    let root: string | undefined;
    effect(() => {
      const next = this.engine.project()?.root;
      if (next !== root) {
        untracked(() => {
          if (root) this.wb.save(root);
          this.wb.clear();
          if (next) this.wb.restore(next);
        });
        root = next;
      }
    });
    effect(() => {
      void this.wb.docs();
      void this.wb.activeId();
      void this.wb.splitId();
      if (root) untracked(() => this.wb.save(root!));
    });
    // Typing in a request or a SQL editor doesn't change the tab list: write drafts down now and then, and on exit.
    const saveDrafts = () => root && this.wb.save(root);
    setInterval(saveDrafts, 5000);
    window.addEventListener('pagehide', saveDrafts);
  }

  async pickFolder(): Promise<void> {
    this.projectMenu.set(false);
    if ('__TAURI_INTERNALS__' in window) {
      const { open } = await import('@tauri-apps/plugin-dialog');
      const folder = await open({ directory: true, title: 'Choose a folder with devdesk.yaml' });
      if (typeof folder === 'string') await this.open(folder);
    }
  }

  async open(path: string, stopCurrent = false): Promise<void> {
    if (!path.trim()) return;
    this.projectMenu.set(false);
    this.switching.set(null);
    this.openError.set(null);
    this.lastPath = path.trim();
    const name = this.engine.project()?.name;
    if (stopCurrent) this.toast(`Stopping ${name}, then opening ${this.base(path.trim())}…`);
    this.busy.set(stopCurrent);
    const r = await this.engine.open(path.trim(), stopCurrent).finally(() => this.busy.set(false));
    if (!r.ok && r.missing) {
      this.setupFor.set(path.trim()); // no devdesk.yaml: the wizard drafts one from what the folder has
      return;
    }
    if (!r.ok) {
      this.openError.set({ error: r.error, problems: r.problems });
      return;
    }
    (document.activeElement as HTMLElement | null)?.blur(); // so number keys switch pages right away
    this.selected.set(null);
    this.drawerFor.set(null);
    this.setPage('services');
    this.remember(this.engine.project()?.root ?? path.trim());
    this.pathInput.set('');
  }

  /** A project in the menu: another one while this one runs goes through the switcher's question. */
  pickProject(path: string): void {
    if (this.engine.running() && path !== this.engine.project()?.root) {
      this.projectMenu.set(false);
      this.switching.set(path);
    } else {
      this.open(path);
    }
  }

  /** Takes a folder off the recent list. Nothing on disk changes. */
  forgetProject(path: string): void {
    this.recent.set(this.recent().filter((r) => r !== path));
    try {
      localStorage.setItem(RECENT_KEY, JSON.stringify(this.recent()));
    } catch {
      /* not kept: fine */
    }
  }

  setPage(p: Page): void {
    this.page.set(p);
    // Back on a Workbench page: show the tab that was open there.
    const last = this.wb.lastOn(p);
    if (last) this.wb.activeId.set(last.id);
    this.saveUi();
  }

  /** A Workbench tab was clicked: show its page, then it. */
  showDoc(doc: Doc): void {
    this.wb.activeId.set(doc.id);
    // The split tab is on the right of every Workbench page: no need to leave this one.
    if (doc.id === this.wb.splitId() && this.isWork()) return;
    this.setPage(pageOf(doc.spec));
  }

  /** A crash in the logs links to the API-client request that ran then: it opens as a new tab, ready to resend. */
  async openSent(id: number): Promise<void> {
    const r = await this.engine.httpSent(id);
    if (!r.ok) {
      this.toast(r.error, 'err');
      return;
    }
    const doc = this.wb.open({ kind: 'request', requestId: null });
    doc.state = new RequestState(r.value);
    this.wb.rename(doc.id, `Resend ${r.value.method}`);
    this.showDoc(doc);
  }

  openDoc(spec: DocSpec): void {
    const doc = this.wb.open(spec);
    this.showDoc(doc);
  }

  toggleLogs(): void {
    this.logsOpen.set(!this.logsOpen());
    this.saveUi();
  }

  /** Drag the bar above the logs; the height is remembered. */
  startResize(e: PointerEvent): void {
    e.preventDefault();
    const startY = e.clientY;
    const startH = this.logsHeight();
    const move = (m: PointerEvent) => {
      const h = Math.min(Math.max(startH + (startY - m.clientY), 120), window.innerHeight - 220);
      this.logsHeight.set(h);
    };
    const up = () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
      document.body.classList.remove('resizing');
      this.saveUi();
    };
    document.body.classList.add('resizing');
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
  }

  toast(text: string, kind: 'info' | 'err' = 'info', why?: string): void {
    const id = ++this.toastId;
    this.toasts.update((t) => [...t.slice(-3), { id, text, kind, why }]);
    setTimeout(() => this.toasts.update((t) => t.filter((x) => x.id !== id)), why ? 12000 : kind === 'err' ? 6000 : 2600);
  }

  dismissToast(id: number): void {
    this.toasts.update((t) => t.filter((x) => x.id !== id));
  }

  /**
   * A service crashed or couldn't start: say so right away, with a way to the explanation. A desktop notification
   * too when DevDesk's window isn't in front: that's when a crash goes unnoticed.
   */
  private async popUpCrash(c: { service: string; label: string; detail: string; reason: string; at: number }): Promise<void> {
    const last = this.lastCrashPopup.get(c.service) ?? 0;
    if (c.at - last < 30_000) return;
    this.lastCrashPopup.set(c.service, c.at);
    const what =
      c.label === 'failed' ? `${c.service} couldn't start` : c.label === 'crash-loop' ? `${c.service} keeps crashing` : `${c.service} crashed`;
    const why = c.detail || c.reason;
    this.toast(why ? `${what}: ${why}` : what, 'err', c.service);
    if (!('__TAURI_INTERNALS__' in window) || document.hasFocus()) return;
    const notification = await import('@tauri-apps/plugin-notification');
    let allowed = await notification.isPermissionGranted();
    if (!allowed) allowed = (await notification.requestPermission()) === 'granted';
    if (allowed) notification.sendNotification({ title: `DevDesk: ${what}`, body: why || 'Open DevDesk to see why.' });
  }

  async start(): Promise<void> {
    const p = this.engine.project();
    if (!p) return;
    if (!p.trusted) {
      this.trustOpen.set(true); // nothing runs before the user has seen exactly what will run
      return;
    }
    this.busy.set(true);
    const err = await this.engine.up();
    this.actionError.set(err);
    if (!err) this.toast(`Starting ${p.name}…`);
    this.busy.set(false);
  }

  async trustAndRun(): Promise<void> {
    await this.engine.trust();
    this.trustOpen.set(false);
    await this.start();
  }

  async stop(): Promise<void> {
    this.actionError.set(null);
    this.busy.set(true);
    this.toast('Stopping everything, dependents first…');
    await this.engine.down();
    this.busy.set(false);
  }

  readonly checking = signal(false);

  /** "Check" runs the doctor: everything `devdesk why` checks, plus versions, Docker, images, packages and disk. */
  async check(): Promise<void> {
    this.checking.set(true);
    await this.engine.doctor();
    this.checking.set(false);
    this.diagnosisFor.set(null);
    this.setPage('diagnosis');
  }

  /** One service: start (with what it needs), stop or restart (with what needs it). The engine decides the rest. */
  async serviceAction(e: { service: string; action: 'start' | 'stop' | 'restart' }): Promise<void> {
    if (!this.engine.project()?.trusted) {
      this.trustOpen.set(true); // nothing runs before the user has seen exactly what will run
      return;
    }
    const err = await this.engine.serviceAction(e.service, e.action);
    this.actionError.set(err);
    if (err) this.toast(err, 'err');
    else
      this.toast(
        e.action === 'start'
          ? `Starting ${e.service} and what it needs…`
          : e.action === 'stop'
            ? `Stopping ${e.service} and what needs it…`
            : `Restarting ${e.service}…`,
      );
  }

  openPalette(): void {
    this.paletteOpen.set(true);
    this.catalog.refresh(); // so new tables show up in the search
  }

  /** A palette pick does exactly what the matching button does. */
  async runCommand(c: PaletteCommand): Promise<void> {
    this.paletteOpen.set(false);
    switch (c.kind) {
      case 'service':
        return this.serviceAction(c);
      case 'logs':
        this.selected.set(c.service);
        this.logsOpen.set(true);
        return;
      case 'why':
        return this.why(c.service);
      case 'details':
        this.selected.set(c.service);
        this.drawerFor.set(c.service);
        return;
      case 'project':
        if (c.action === 'snapshots') {
          this.snapshotsOpen.set(true);
          return;
        }
        return c.action === 'up' ? this.start() : c.action === 'down' ? this.stop() : this.check();
      case 'tab':
        this.setPage(c.tab as Page);
        return;
      case 'data':
        this.openData(c.service);
        return;
      case 'open':
        this.openDoc(c.spec);
        return;
      case 'port':
        this.portFilter.set(c.port);
        this.setPage('ports');
        return;
    }
  }

  /** Opens a database in the Data page (from the drawer, the graph or the palette). */
  openData(service: string): void {
    this.drawerFor.set(null);
    const db = this.catalog.databases().find((d) => d.service === service);
    if (db?.kind === 'redis') this.openDoc({ kind: 'redis', service, pattern: '*' });
    else this.setPage('data');
  }

  why(service: string): void {
    this.diagnosisFor.set(service);
    this.setPage('diagnosis');
  }

  @HostListener('document:keydown', ['$event'])
  keys(e: KeyboardEvent): void {
    if (this.setupFor() || this.freshFor() || this.envOpen() || this.snapshotsOpen() || this.remoteOpen() || this.logSearch() || this.shellFor() || this.switching() !== null) return;
    if (e.ctrlKey && !e.shiftKey && e.key.toLowerCase() === 'o') {
      e.preventDefault();
      this.projectMenu.set(false);
      this.paletteOpen.set(false);
      this.switching.set(''); // works with no project open too: it's how you get to one
      return;
    }
    if (e.ctrlKey && e.shiftKey && e.key.toLowerCase() === 'f' && this.engine.project()) {
      e.preventDefault();
      this.logSearch.set(true); // search everything the services printed, not only what's on screen
      return;
    } // a dialog is open: no page switching or Start all behind it
    if (e.key === 'Escape') {
      this.trustOpen.set(false);
      this.drawerFor.set(null);
      this.paletteOpen.set(false);
      this.projectMenu.set(false);
    }
    const typing = (e.target as HTMLElement)?.closest?.('input, select, textarea');
    const k = e.key.toLowerCase();
    if (!typing && !e.ctrlKey && !e.altKey && !e.metaKey && /^[0-9]$/.test(e.key) && this.engine.project()) {
      const page = PAGES[e.key === '0' ? 9 : Number(e.key) - 1]; // 0 is the tenth, like on the keyboard
      if (page) this.setPage(page.id);
    }
    if (e.ctrlKey && k === 'k' && this.engine.project()) {
      e.preventDefault();
      this.paletteOpen() ? this.paletteOpen.set(false) : this.openPalette();
    }
    if (e.ctrlKey && e.key === '\\' && this.isWork()) {
      e.preventDefault();
      if (!this.wb.toggleSplit()) this.toast('Open a second tab to put beside this one');
      else if (this.wb.active()) this.showDoc(this.wb.active()!);
    }
    if (e.ctrlKey && k === 'j') {
      e.preventDefault();
      this.toggleLogs();
    }
    if (e.ctrlKey && k === 'w' && this.isWork()) {
      e.preventDefault();
      // The half that has the focus: the split tab, or this page's own.
      const a = this.wb.active();
      const doc = a && (a.id === this.wb.splitId() || pageOf(a.spec) === this.page()) ? a : this.wb.lastOn(this.page());
      if (doc) this.wb.close(doc.id);
    }
    // Never on Work pages: there Ctrl+Enter means Run/Send, and a press with the focus outside the editor
    // (after clicking a result, say) must not stop the whole project.
    if (e.ctrlKey && e.key === 'Enter' && !typing && !e.defaultPrevented && !this.isWork()) {
      this.engine.running() ? this.stop() : this.start();
    }
  }

  /** Opening failed because an env file is missing: a fresh clone, which "Set up" prepares. */
  readonly needsSetup = computed(() => !!this.openError()?.problems?.some((p) => p.path === 'env_files'));

  freshSetup(path?: string): void {
    this.projectMenu.set(false);
    // After a failed open, the folder that failed; otherwise the open project.
    const folder = path ?? (this.needsSetup() || !this.engine.project() ? this.lastPath : this.engine.project()!.root);
    if (folder) this.freshFor.set(folder);
  }

  async freshDone(path: string): Promise<void> {
    this.freshFor.set(null);
    await this.open(path);
  }

  /** Switches profile; the engine refuses while services run (they'd keep the old settings). */
  async selectProfile(name: string): Promise<void> {
    const error = await this.engine.selectProfile(name);
    if (error) this.toast(error, 'err');
    else this.toast(name ? `Profile "${name}": its settings apply from the next start` : 'Default settings (no profile)');
  }

  editSetup(): void {
    this.projectMenu.set(false);
    const root = this.engine.project()?.root;
    if (root) this.setupFor.set(root);
  }

  async setupDone(path: string): Promise<void> {
    this.setupFor.set(null);
    await this.open(path); // the engine already opened it; this switches the app to it and remembers it
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

  private validPage(p: unknown): Page {
    return PAGES.find((x) => x.id === p)?.id ?? 'services';
  }

  private loadUi(): { page?: Page; logs?: number; logsOpen?: boolean } {
    try {
      return JSON.parse(localStorage.getItem(UI_KEY) ?? '{}');
    } catch {
      return {};
    }
  }

  private saveUi(): void {
    try {
      localStorage.setItem(UI_KEY, JSON.stringify({ page: this.page(), logs: this.logsHeight(), logsOpen: this.logsOpen() }));
    } catch {
      /* not kept: fine */
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

  keyOf(p: Page): number {
    return (PAGES.findIndex((x) => x.id === p) + 1) % 10;
  }
}
