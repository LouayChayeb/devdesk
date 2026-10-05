import { Component, computed, inject, input, output, signal } from '@angular/core';
import { Engine, Finding } from './engine';

/** DESIGN.md §4.7: finding cards, root cause first; fixes are copy-only, never run from here. */
@Component({
  selector: 'dd-diagnosis',
  template: `
    @if (only(); as svc) {
      <div class="filter">Why <b>{{ svc }}</b> is broken <button class="ghost" (click)="showAll.emit()">Show all</button></div>
    }
    @if (cards().length === 0) {
      <div class="none">
        @if (engine.preflight() !== null) {
          No problems found: ports are free, programs are installed, settings from .env.example are set.
        } @else {
          No findings. When something fails, DevDesk explains it here. Use <b>Check</b> to look for problems before starting.
        }
      </div>
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
  `,
})
export class DiagnosisPanel {
  readonly engine = inject(Engine);
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
