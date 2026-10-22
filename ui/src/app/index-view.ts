import { Component, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { Engine, SearchHits } from './engine';
import { Doc } from './workbench';
import { Icon } from './icon';

/**
 * One Elasticsearch / OpenSearch index: search it (plain words, field:value, or the engine's own JSON), read the
 * matching documents, and see its fields. Read-only, like the engine's side.
 */
@Component({
  selector: 'dd-index-view',
  imports: [Icon],
  template: `
    <div class="bar">
      <input class="mono q" [value]="query()" (input)="query.set($any($event.target).value)" (keydown.enter)="search(0)"
             placeholder='status:paid AND total:>100   ·   or JSON: {"query": {"match": {"item": "lamp"}}}' spellcheck="false" />
      <button class="primary sm" (click)="search(0)"><dd-icon name="search" [size]="12" /> Search</button>
      <button class="ghost sm" [class.on]="showMapping()" (click)="toggleMapping()">Fields</button>
    </div>
    @if (error(); as e) { <p class="problem small">{{ e }}</p> }
    @if (showMapping()) {
      <pre class="mono mapping">{{ mapping() ?? 'Reading…' }}</pre>
    }
    @if (hits(); as h) {
      <div class="dim small">
        {{ h.exact ? '' : 'more than ' }}{{ h.total.toLocaleString('en-US') }} document{{ h.total === 1 ? '' : 's' }} · {{ h.tookMs }} ms
        @if (h.total > from() + h.hits.length) { · showing {{ from() + 1 }}–{{ from() + h.hits.length }} }
      </div>
      <div class="hits">
        @for (x of h.hits; track x.id) {
          <div class="hit">
            <div class="hhead mono small"><span class="id">{{ x.id }}</span><span class="dim">score {{ x.score.toFixed(2) }}</span></div>
            <pre class="mono">{{ x.source }}</pre>
          </div>
        } @empty {
          <p class="dim">No document matches.</p>
        }
      </div>
      <div class="pager">
        <button class="ghost sm" [disabled]="from() === 0" (click)="search(from() - size)">← Previous</button>
        <button class="ghost sm" [disabled]="from() + h.hits.length >= h.total" (click)="search(from() + size)">Next →</button>
      </div>
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; padding: 10px 14px; gap: 8px; overflow: auto; }
    .bar { display: flex; gap: 6px; }
    .q { flex: 1; height: 30px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 8px; }
    .mapping { max-height: 30vh; overflow: auto; margin: 0; font-size: var(--fs-sm); background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 8px 10px; }
    .hits { display: flex; flex-direction: column; gap: 6px; }
    .hit { border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-1); padding: 6px 10px; }
    .hhead { display: flex; justify-content: space-between; margin-bottom: 4px; }
    .id { color: var(--accent); }
    .hit pre { margin: 0; font-size: var(--fs-sm); white-space: pre-wrap; word-break: break-word; max-height: 260px; overflow: auto; }
    .pager { display: flex; gap: 6px; }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); margin: 0; }
  `,
})
export class IndexView {
  readonly engine = inject(Engine);
  readonly doc = input.required<Doc>();
  readonly query = signal('');
  readonly hits = signal<SearchHits | null>(null);
  readonly from = signal(0);
  readonly error = signal<string | null>(null);
  readonly showMapping = signal(false);
  readonly mapping = signal<string | null>(null);
  readonly size = 20;

  readonly service = computed(() => (this.doc().spec.kind === 'index' ? (this.doc().spec as { service: string }).service : ''));
  readonly index = computed(() => (this.doc().spec.kind === 'index' ? (this.doc().spec as { index: string }).index : ''));

  constructor() {
    effect(() => {
      this.doc();
      untracked(() => this.search(0));
    });
  }

  async search(from: number): Promise<void> {
    this.error.set(null);
    this.from.set(Math.max(0, from));
    const r = await this.engine.search(this.service(), this.index(), this.query(), this.from(), this.size);
    if (r.ok) this.hits.set(r.value);
    else this.error.set(r.error);
  }

  async toggleMapping(): Promise<void> {
    this.showMapping.set(!this.showMapping());
    if (this.showMapping() && this.mapping() === null) {
      const r = await this.engine.searchMapping(this.service(), this.index());
      if (r.ok) this.mapping.set(r.value);
      else this.error.set(r.error);
    }
  }
}
