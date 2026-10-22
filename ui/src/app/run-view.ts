import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Engine, RunView as Run } from './engine';
import { Requests } from './requests';
import { Doc, Workbench } from './workbench';
import { RequestState } from './request-view';
import { Icon } from './icon';

class RunState {
  readonly run = signal<Run | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  started = false;
}

/** A collection run: every saved request of it, sent in order, with what each check found. */
@Component({
  selector: 'dd-run-view',
  imports: [Icon],
  template: `
    @let st = s();
    <div class="bar">
      <span class="title"><dd-icon name="play" [size]="13" /> Run <b>{{ collection() || 'No collection' }}</b></span>
      @if (st.run(); as r) {
        <span class="sum" [class.bad]="r.passed < r.total">{{ r.passed }} / {{ r.total }} passed</span>
      }
      <span class="grow"></span>
      <button class="sm primary" [class.busy]="st.busy()" (click)="start()"><dd-icon name="restart" [size]="12" /> Run again</button>
    </div>
    @if (st.error(); as e) { <div class="error mono">{{ e }}</div> }
    @if (st.run(); as r) {
      <div class="list">
        @for (x of r.results; track $index) {
          <div class="row" [class.bad]="!x.ok">
            <dd-icon [name]="x.ok ? 'check' : 'close'" [size]="13" class="mark" />
            <span class="m mono m-{{ x.method }}">{{ x.method }}</span>
            <a class="name" (click)="open(x.id)">{{ x.name }}</a>
            <span class="url mono dim" [title]="x.url">{{ x.url }}</span>
            <span class="grow"></span>
            @if (x.error) {
              <span class="why">{{ x.error }}</span>
            } @else if (x.outcome === 'failed') {
              <span class="why">{{ x.explanation }}</span>
            } @else {
              <span class="status mono" [class.bad]="(x.status ?? 0) >= 400">{{ x.status }}</span>
              <span class="mono dim">{{ x.ms }} ms</span>
            }
          </div>
          @for (c of x.checks ?? []; track $index) {
            <div class="check" [class.fail]="!c.passed">
              <dd-icon [name]="c.passed ? 'check' : 'close'" [size]="11" /> {{ c.label }} @if (!c.passed) { <span class="dim">· got {{ c.actual }}</span> }
            </div>
          }
        }
      </div>
    } @else if (st.busy()) {
      <div class="none">Sending the requests one by one…</div>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; height: 100%; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 12px; padding: 8px 14px; border-bottom: 1px solid var(--border); }
    .title { display: inline-flex; align-items: center; gap: 6px; }
    .title dd-icon { color: var(--accent); }
    .sum { font: var(--fs-sm) var(--font-mono); color: var(--accent); background: var(--accent-deep); border-radius: 999px; padding: 1px 10px; }
    .sum.bad { color: var(--err); background: var(--err-bg); }
    .grow { flex: 1; }
    .dim { color: var(--text-3); }
    .error { margin: 10px 14px; padding: 10px 12px; background: var(--err-bg); border: 1px solid rgba(240, 100, 90, 0.4); border-radius: var(--radius); white-space: pre-wrap; font-size: var(--fs-sm); }
    .list { padding: 8px 14px; overflow: auto; }
    .row { display: flex; align-items: center; gap: 10px; padding: 7px 4px; border-bottom: 1px solid var(--border); }
    .mark { color: var(--accent); }
    .row.bad .mark { color: var(--err); }
    .m { font-size: 10px; font-weight: 600; width: 46px; }
    .m-GET { color: var(--accent); } .m-POST { color: var(--warn); } .m-PUT, .m-PATCH { color: #7ec4f0; } .m-DELETE { color: var(--err); }
    .name { color: var(--text-1); cursor: pointer; }
    .url { font-size: var(--fs-xs); max-width: 380px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .status { color: var(--accent); }
    .status.bad { color: var(--err); }
    .why { color: var(--err); font-size: var(--fs-sm); max-width: 520px; }
    .check { font: var(--fs-xs) var(--font-mono); color: var(--accent); padding: 2px 0 2px 34px; display: flex; gap: 6px; align-items: center; }
    .check.fail { color: var(--err); }
    .none { color: var(--text-3); padding: 16px 14px; }
  `,
})
export class RunViewComponent {
  readonly engine = inject(Engine);
  readonly requests = inject(Requests);
  readonly wb = inject(Workbench);
  readonly doc = input.required<Doc>();
  readonly show = output<Doc>();
  readonly collection = computed(() => (this.doc().spec as { collection: string }).collection);
  readonly s = computed(() => {
    const d = this.doc();
    if (!(d.state instanceof RunState)) d.state = new RunState();
    return d.state as RunState;
  });

  constructor() {
    effect(() => {
      const st = this.s();
      if (!st.started) {
        st.started = true;
        untracked(() => this.start());
      }
    });
  }

  async start(): Promise<void> {
    const st = this.s();
    st.busy.set(true);
    const r = await this.engine.httpRun(this.collection());
    st.busy.set(false);
    st.error.set(r.ok ? null : r.error);
    if (r.ok) st.run.set(r.value);
    this.requests.refresh(); // captured variables and history
  }

  open(id: string): void {
    const r = this.requests.saved().find((x) => x.id === id);
    if (!r) return;
    const doc = this.wb.open({ kind: 'request', requestId: id });
    if (!(doc.state instanceof RequestState)) {
      doc.state = new RequestState(r);
      this.wb.rename(doc.id, r.name);
    }
    this.show.emit(doc);
  }
}
