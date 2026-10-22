import { Component, OnDestroy, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { Engine, Finding, MigrationSource } from './engine';
import { saveAnyPath } from './files';

/** DESIGN.md §4.7: finding cards, root cause first; fixes are copy-only, never run from here. */
@Component({
  selector: 'dd-diagnosis',
  template: `
    @if (only(); as svc) {
      <div class="filter">Why <b>{{ svc }}</b> is broken <button class="ghost" (click)="showAll.emit()">Show all</button></div>
    }
    @if (!only() && (fine().length || (engine.passed() ?? []).length)) {
      <section class="passed">
        <div class="block-label">Checked and fine</div>
        @for (p of engine.passed() ?? []; track $index) {
          <div class="ok"><span class="tick">✓</span> {{ p }}</div>
        }
        @for (p of fine(); track $index) {
          <div class="ok"><span class="tick">✓</span> {{ p }}</div>
        }
      </section>
    }
    @if (cards().length === 0 && migrationCards().length === 0) {
      <div class="none">
        @if (engine.preflight() !== null) {
          No problems found: this machine can run the project.
        } @else {
          No findings. When something fails, DevDesk explains it here. Use <b>Check</b> to look for problems before starting.
        }
      </div>
    }
    @if (!only()) {
      <section class="changes">
        <div class="chead">
          <span class="ctitle">What changed since it last worked?</span>
          <span class="grow"></span>
          <button class="ghost" [disabled]="bundling()" (click)="bundle()" title="One zip with states, findings, logs, the last day's timeline and settings, secrets taken out: to attach to an issue or a chat">{{ bundling() ? 'Packing…' : 'Crash bundle…' }}</button>
          <button class="ghost" [disabled]="comparing()" (click)="compare()">{{ comparing() ? 'Comparing…' : changes() ? 'Compare again' : 'Compare with the last clean start' }}</button>
        </div>
        @if (bundled(); as b) { <div class="ok small"><span class="tick">✓</span> {{ b }}</div> }
        @if (changes(); as c) {
          @if (!c.known) {
            <div class="dim small">{{ c.message }}</div>
          } @else if (!c.changes?.length) {
            <div class="ok small"><span class="tick">✓</span> Nothing in the setup changed since {{ when(c.since!) }}: look at the code, or the services' logs.</div>
          } @else {
            <div class="dim small">Since the last clean start, {{ when(c.since!) }}:</div>
            @for (x of c.changes; track $index) {
              <div class="change"><span class="area mono">{{ x.area }}</span><span class="mono what">{{ x.what }}</span><span class="detail">{{ x.detail }}</span></div>
            }
          }
        }
      </section>
      @for (m of migrationCards(); track m.folder) {
        <article class="card warning">
          <header>
            <span class="sev">!</span>
            <span class="title">{{ m.title }}</span>
            <span class="svc mono">{{ m.database ?? m.folder }}</span>
          </header>
          <p>{{ m.explanation }}</p>
          <div class="block-label">Evidence <span class="mono">· {{ m.folder }}</span></div>
          <pre class="evidence">@for (e of m.evidence ?? []; track $index) {{{ e }}
}</pre>
          @if (m.actions?.length) {
            <div class="block-label">Suggested actions</div>
            @for (a of m.actions; track $index) {
              <div class="action">
                <code>{{ a }}</code>
                <button class="ghost copy" (click)="copy(a)">{{ copied() === a ? 'Copied' : 'Copy' }}</button>
              </div>
            }
          }
        </article>
      }
    }
    @for (f of cards(); track $index) {
      <article class="card {{ f.severity }}">
        <header>
          <span class="sev">{{ f.severity === 'error' ? '✕' : '!' }}</span>
          <span class="title">{{ f.title }}</span>
          <span class="svc mono">{{ f.service }}</span>
        </header>
        <p>{{ f.explanation }}</p>
        @if (f.evidence.length) {
          <div class="block-label">Evidence</div>
          <pre class="evidence">@for (e of f.evidence; track $index) {{{ e }}
}</pre>
        }
        @if (f.actions.length) {
          <div class="block-label">Suggested actions</div>
          @for (a of f.actions; track $index) {
            <div class="action">
              <code>{{ a }}</code>
              <button class="ghost copy" (click)="copy(a)">{{ copied() === a ? 'Copied' : 'Copy' }}</button>
            </div>
          }
        }
      </article>
    }
  `,
  styles: `
    :host { display: block; padding: 16px; max-width: 920px; }
    .filter { color: var(--text-2); margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }
    .none { color: var(--text-2); padding: 24px 0; }
    .card { background: var(--bg-1); border: 1px solid var(--border); border-left: 3px solid var(--err); border-radius: var(--radius); padding: 12px 16px; margin-bottom: 12px; }
    .card.warning { border-left-color: var(--warn); }
    header { display: flex; align-items: center; gap: 8px; }
    .sev { color: var(--err); font-weight: 600; }
    .warning .sev { color: var(--warn); }
    .title { font: 600 var(--fs-lg) var(--font-ui); flex: 1; }
    .svc { color: var(--text-3); font-size: var(--fs-sm); }
    p { color: var(--text-1); margin: 8px 0 12px; }
    .block-label { font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; margin: 8px 0 4px; }
    .evidence { font: 400 var(--fs-sm) / 1.55 var(--font-mono); color: var(--text-2); background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius); padding: 8px 12px; margin: 0; white-space: pre-wrap; word-break: break-word; }
    .action { display: flex; align-items: center; gap: 8px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius); padding: 4px 4px 4px 12px; margin-bottom: 4px; }
    code { font: 400 var(--fs-sm) var(--font-mono); flex: 1; white-space: pre-wrap; word-break: break-word; }
    .copy { font-size: var(--fs-sm); padding: 2px 8px; }
    .passed { background: var(--bg-1); border: 1px solid var(--border); border-left: 3px solid var(--ok); border-radius: var(--radius); padding: 10px 16px; margin-bottom: 12px; }
    .ok { color: var(--text-2); font-size: var(--fs-sm); padding: 2px 0; }
    .tick { color: var(--ok); font-weight: 600; margin-right: 4px; }
    .changes { background: var(--bg-1); border: 1px solid var(--border); border-left: 3px solid var(--info); border-radius: var(--radius); padding: 10px 16px; margin-bottom: 12px; display: flex; flex-direction: column; gap: 4px; }
    .chead { display: flex; align-items: center; gap: 8px; }
    .ctitle { font: 600 var(--fs-md) var(--font-ui); }
    .grow { flex: 1; }
    .change { display: flex; gap: 10px; font-size: var(--fs-sm); align-items: baseline; }
    .area { flex: none; width: 90px; color: var(--text-3); }
    .what { flex: none; max-width: 40%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .detail { color: var(--text-2); }
  `,
})
export class DiagnosisPanel implements OnInit, OnDestroy {
  readonly engine = inject(Engine);
  /** Migration folders of the project compared with their databases (checked every 10 s while this page shows). */
  readonly migrations = signal<MigrationSource[]>([]);
  readonly migrationCards = computed(() => this.migrations().filter((m) => m.title));
  readonly fine = computed(() => this.migrations().flatMap((m) => (m.fine ? [m.fine] : [])));
  private timer?: ReturnType<typeof setInterval>;
  readonly changes = signal<{ known: boolean; message?: string; since?: string; changes?: { area: string; what: string; detail: string }[] } | null>(null);
  readonly comparing = signal(false);
  readonly bundling = signal(false);
  readonly bundled = signal<string | null>(null);

  async bundle(): Promise<void> {
    const name = `${this.engine.project()?.name ?? 'project'}-crash-bundle.zip`;
    const inApp = '__TAURI_INTERNALS__' in window;
    const path = inApp ? await saveAnyPath(name) : null;
    if (inApp && path === null) return; // cancelled
    this.bundling.set(true);
    const r = await this.engine.crashBundle(path);
    this.bundling.set(false);
    this.bundled.set(r.ok ? `Saved ${r.value.path} (${Math.ceil(r.value.bytes / 1024)} KB). Secrets are replaced by ***; read it once before sharing.` : r.error);
  }

  /** Asks the engine on click: it checks tool versions and images, which takes a few seconds. */
  async compare(): Promise<void> {
    this.comparing.set(true);
    const r = await this.engine.changes();
    this.comparing.set(false);
    this.changes.set(r.ok ? r.value : { known: false, message: r.error });
  }

  when(iso: string): string {
    const d = new Date(iso);
    return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }) + ' ' + d.toLocaleTimeString('en-GB', { hour12: false, hour: '2-digit', minute: '2-digit' });
  }

  ngOnInit(): void {
    this.loadMigrations();
    this.timer = setInterval(() => this.loadMigrations(), 10000);
  }

  ngOnDestroy(): void {
    clearInterval(this.timer);
  }

  private async loadMigrations(): Promise<void> {
    const r = await this.engine.migrations();
    this.migrations.set(r.ok ? r.value : []);
  }
  readonly only = input<string | null>(null);
  readonly showAll = output<void>();
  readonly copied = signal<string | null>(null);

  readonly cards = computed<Finding[]>(() => {
    const live = this.engine.allFindings();
    const pre = this.engine.preflight() ?? [];
    const all = [...live, ...pre.filter((p) => !live.some((l) => l.title === p.title && l.service === p.service))];
    const svc = this.only();
    return svc ? all.filter((f) => f.service === svc || this.chainOf(svc).includes(f.service)) : all;
  });

  /** The services a blocked service waits on (from the engine's blocked cause), to show the root cause too. */
  private chainOf(service: string): string[] {
    const chain: string[] = [];
    let current = this.engine.project()?.services.find((s) => s.name === service);
    while (current?.state.cause && !chain.includes(current.state.cause)) {
      chain.push(current.state.cause);
      current = this.engine.project()?.services.find((s) => s.name === current!.state.cause);
    }
    return chain;
  }

  async copy(text: string): Promise<void> {
    await navigator.clipboard.writeText(text);
    this.copied.set(text);
    setTimeout(() => this.copied.set(null), 1500);
  }
}
