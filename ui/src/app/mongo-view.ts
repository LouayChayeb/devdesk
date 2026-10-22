import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Engine } from './engine';
import { Doc } from './workbench';
import { Icon } from './icon';

const PAGE = 50;

class MongoState {
  readonly filter = signal('');
  readonly sort = signal('');
  readonly skip = signal(0);
  readonly docs = signal<string[]>([]);
  readonly matching = signal(0);
  readonly ms = signal(0);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  /** The document being edited (its index on the page) and its text. */
  readonly editing = signal<{ index: number; text: string } | null>(null);
  readonly inserting = signal<string | null>(null);
  loaded = false;
}

/** One MongoDB collection: filter and sort as MongoDB JSON, documents as JSON, edit, insert and delete. */
@Component({
  selector: 'dd-mongo-view',
  imports: [Icon],
  template: `
    @let st = s();
    <div class="bar">
      <span class="mono where">{{ spec().db }}.<b>{{ spec().collection }}</b></span>
      <input class="mono grow-in" placeholder='filter, e.g. {"status": "paid", "total": {"$gt": 40}}' [value]="st.filter()" (input)="st.filter.set($any($event.target).value)" (keydown.enter)="search()" />
      <input class="mono sort" placeholder='sort, e.g. {"_id": -1}' [value]="st.sort()" (input)="st.sort.set($any($event.target).value)" (keydown.enter)="search()" />
      <button class="sm primary" [class.busy]="st.busy()" (click)="search()"><dd-icon name="search" [size]="12" /> Find</button>
      <button class="sm" (click)="st.inserting.set(st.inserting() === null ? '{\\n  \\n}' : null)"><dd-icon name="plus" [size]="12" /> Document</button>
    </div>
    @if (st.error(); as e) { <div class="error mono">{{ e }}</div> }
    @if (st.inserting() !== null) {
      <div class="card new">
        <textarea class="mono" spellcheck="false" [value]="st.inserting()" (input)="st.inserting.set($any($event.target).value)"></textarea>
        <div class="row"><span class="dim small">A new document; MongoDB gives it an _id. Dates as {{ dateHint }}.</span><span class="grow"></span>
          <button class="ghost sm" (click)="st.inserting.set(null)">Cancel</button>
          <button class="primary sm" (click)="insert()"><dd-icon name="save" [size]="12" /> Insert</button>
        </div>
      </div>
    }
    <div class="meta mono dim">
      {{ st.matching().toLocaleString() }} document{{ st.matching() === 1 ? '' : 's' }}{{ st.filter().trim() ? ' match' : '' }} · {{ st.ms() }} ms
      <span class="grow"></span>
      <button class="ghost sm" [disabled]="st.skip() === 0" (click)="page(-1)">← Previous</button>
      <span>{{ st.docs().length ? st.skip() + 1 : 0 }}–{{ st.skip() + st.docs().length }}</span>
      <button class="ghost sm" [disabled]="st.skip() + st.docs().length >= st.matching()" (click)="page(1)">Next →</button>
    </div>
    <div class="list">
      @for (d of st.docs(); track $index; let i = $index) {
        <div class="card">
          @if (st.editing()?.index === i) {
            <textarea class="mono" spellcheck="false" [value]="st.editing()!.text" (input)="st.editing.set({ index: i, text: $any($event.target).value })"></textarea>
            <div class="row"><span class="grow"></span>
              <button class="ghost sm" (click)="st.editing.set(null)">Cancel</button>
              <button class="primary sm" (click)="save(i)"><dd-icon name="save" [size]="12" /> Save</button>
            </div>
          } @else {
            <pre class="mono">{{ d }}</pre>
            <div class="tools">
              <button class="ghost icon sm" title="Edit this document" (click)="st.editing.set({ index: i, text: d })"><dd-icon name="edit" [size]="12" /></button>
              <button class="ghost icon sm" title="Copy as JSON" (click)="copy(d)"><dd-icon name="copy" [size]="12" /></button>
              <button class="ghost icon sm" title="Delete this document" (click)="remove(i)"><dd-icon name="trash" [size]="12" /></button>
            </div>
          }
        </div>
      } @empty {
        @if (!st.busy()) { <div class="dim pad">No documents{{ st.filter().trim() ? ' match this filter' : ' in this collection' }}.</div> }
      }
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 6px; padding: 8px 14px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
    .where { color: var(--text-2); margin-right: 4px; }
    .where b { color: var(--text-1); }
    .grow-in { flex: 1; min-width: 220px; }
    .sort { width: 180px; }
    .error { margin: 8px 14px 0; padding: 8px 12px; background: var(--err-bg); border-radius: var(--radius); font-size: var(--fs-sm); white-space: pre-wrap; }
    .meta { display: flex; align-items: center; gap: 8px; padding: 6px 14px; font-size: var(--fs-sm); }
    .grow { flex: 1; }
    .list { flex: 1; overflow: auto; padding: 0 14px 14px; display: flex; flex-direction: column; gap: 6px; }
    .card { position: relative; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-1); }
    .card.new { margin: 8px 14px 0; padding: 8px; border-color: var(--accent-line); }
    pre { margin: 0; padding: 8px 12px; font-size: var(--fs-sm); white-space: pre-wrap; word-break: break-word; max-height: 360px; overflow: auto; }
    .tools { position: absolute; top: 4px; right: 4px; display: flex; gap: 2px; opacity: 0; }
    .card:hover .tools { opacity: 1; }
    textarea { width: 100%; min-height: 180px; resize: vertical; background: var(--bg-0); color: var(--text-1); border: 1px solid var(--border); border-radius: var(--radius); padding: 8px 10px; font-size: var(--fs-sm); }
    .row { display: flex; align-items: center; gap: 6px; padding: 6px; }
    .pad { padding: 12px 0; }
  `,
})
export class MongoViewComponent {
  readonly engine = inject(Engine);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();
  readonly dateHint = '{"$date": "2026-10-08T10:00:00Z"}';

  readonly s = computed(() => {
    const d = this.doc();
    if (!(d.state instanceof MongoState)) d.state = new MongoState();
    return d.state as MongoState;
  });
  readonly spec = computed(() => this.doc().spec as { service: string; db: string; collection: string });

  constructor() {
    effect(() => {
      const st = this.s();
      if (!st.loaded) {
        st.loaded = true;
        untracked(() => this.load());
      }
    });
  }

  search(): void {
    this.s().skip.set(0);
    this.load();
  }

  page(dir: number): void {
    this.s().skip.update((x) => Math.max(0, x + dir * PAGE));
    this.load();
  }

  async load(): Promise<void> {
    const st = this.s();
    const { service, db, collection } = this.spec();
    st.busy.set(true);
    const r = await this.engine.mongoFind(service, { db, collection, filter: st.filter(), sort: st.sort(), skip: st.skip(), limit: PAGE });
    st.busy.set(false);
    if (!r.ok) {
      st.error.set(r.error);
      return;
    }
    st.error.set(null);
    st.editing.set(null);
    st.docs.set(r.value.documents);
    st.matching.set(r.value.matching);
    st.ms.set(r.value.ms);
  }

  /** The _id of a shown document, as JSON (what the engine finds it by). */
  private idOf(text: string): string {
    const parsed = JSON.parse(text);
    return JSON.stringify(parsed._id);
  }

  async save(i: number): Promise<void> {
    const st = this.s();
    const e = st.editing();
    if (!e) return;
    const { service, db, collection } = this.spec();
    const r = await this.engine.mongoAction(service, 'replace', { db, collection, id: this.idOf(st.docs()[i]), document: e.text });
    if (!r.ok) return st.error.set(r.error);
    this.toast.emit('Saved the document');
    await this.load();
  }

  async insert(): Promise<void> {
    const st = this.s();
    const { service, db, collection } = this.spec();
    const r = await this.engine.mongoAction(service, 'insert', { db, collection, document: st.inserting() ?? '' });
    if (!r.ok) return st.error.set(r.error);
    st.inserting.set(null);
    this.toast.emit('Inserted a document');
    await this.load();
  }

  async remove(i: number): Promise<void> {
    if (!confirm('Delete this document?')) return;
    const st = this.s();
    const { service, db, collection } = this.spec();
    const r = await this.engine.mongoAction(service, 'delete', { db, collection, id: this.idOf(st.docs()[i]) });
    if (!r.ok) return st.error.set(r.error);
    await this.load();
  }

  copy(text: string): void {
    navigator.clipboard?.writeText(text);
    this.toast.emit('Copied');
  }
}
