import { Component, ElementRef, computed, effect, inject, input, output, signal, untracked, viewChild } from '@angular/core';
import { Engine, KeyInfoView, KeyValueView, RedisEdit } from './engine';
import { Autofocus } from './autofocus';
import { Doc, Workbench } from './workbench';
import { Icon } from './icon';
import { StreamGroups } from './stream-groups';
import { download, openFile, savePath } from './files';

/** A folder of the key tree: "user:1:name" sits in user → 1. */
interface Node {
  name: string;
  path: string;
  depth: number;
  folder: boolean;
  count: number;
  key?: KeyInfoView;
}

/** One open key browser (a pattern on one Redis): its keys, the selected key and the command log. */
class RedisState {
  /** The pattern the key list shows. Lives here (not only on the tab's spec) so a new search is seen everywhere. */
  readonly pattern: ReturnType<typeof signal<string>>;
  readonly keys = signal<KeyInfoView[]>([]);
  readonly cursor = signal('0');
  readonly done = signal(false);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly open = signal<Set<string>>(new Set());
  readonly selected = signal<string | null>(null);
  readonly value = signal<KeyValueView | null>(null);
  readonly valueError = signal<string | null>(null);
  readonly log = signal<{ line: string; reply: string; error: boolean }[]>([]);
  readonly typed: string[] = [];
  readonly confirm = signal<{ line: string; why: string } | null>(null);
  readonly deleting = signal(false);
  readonly pretty = signal(true);
  /** The value cell being typed in ("text", "f:name", "i:3", "s:member"). */
  readonly editing = signal<string | null>(null);
  /** A remove button clicked once: the second click removes (deleting asks first). */
  readonly armed = signal<string | null>(null);
  readonly creating = signal(false);
  /** Delete by pattern: what would go, shown before anything is deleted. */
  readonly bulk = signal<{ pattern: string; count: number; sample: string[] } | null>(null);
  loaded = false;

  constructor(pattern: string) {
    this.pattern = signal(pattern);
  }
}

const TYPE_COLORS: Record<string, string> = { string: 'str', list: 'lst', set: 'set', hash: 'hsh', zset: 'zst', stream: 'stm' };

/** The Redis explorer: keys as a tree by ':', every value type, TTL, delete (asks first) and a command box. */
@Component({
  selector: 'dd-redis-view',
  imports: [Icon, Autofocus, StreamGroups],
  templateUrl: './redis-view.html',
  styleUrl: './redis-view.css',
})
export class RedisView {
  readonly engine = inject(Engine);
  readonly wb = inject(Workbench);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();
  readonly Math = Math;
  /** The type picked in the "new key" form. */
  readonly newType = signal('string');
  private readonly cmd = viewChild<ElementRef<HTMLInputElement>>('cmd');

  readonly s = computed(() => {
    const d = this.doc();
    if (!(d.state instanceof RedisState)) d.state = new RedisState((d.spec as { pattern: string }).pattern);
    return d.state as RedisState;
  });
  readonly spec = computed(() => this.doc().spec as { kind: 'redis'; service: string; pattern: string });

  /** The keys as a tree, flattened to the rows that are visible (folders open or closed). */
  readonly rows = computed<Node[]>(() => {
    const s = this.s();
    const open = s.open();
    const root = new Map<string, any>();
    for (const k of s.keys()) {
      const parts = k.key.split(':');
      let level = root;
      let path = '';
      for (let i = 0; i < parts.length - 1; i++) {
        path += parts[i] + ':';
        if (!level.has(path)) level.set(path, { name: parts[i], path, children: new Map(), count: 0 });
        const f = level.get(path);
        f.count++;
        level = f.children;
      }
      level.set('\0' + k.key, { key: k });
    }
    // Small result: everything open, so a handful of keys is visible at once.
    const all = s.keys().length <= 40;
    const out: Node[] = [];
    const walk = (level: Map<string, any>, depth: number) => {
      const entries = [...level.values()].sort((a, b) => {
        if (!!a.key !== !!b.key) return a.key ? 1 : -1; // folders first
        return (a.key?.key ?? a.name).localeCompare(b.key?.key ?? b.name, undefined, { numeric: true });
      });
      for (const e of entries) {
        if (e.key) {
          const name = e.key.key.split(':').pop();
          out.push({ name: name || e.key.key, path: e.key.key, depth, folder: false, count: 0, key: e.key });
        } else {
          out.push({ name: e.name, path: e.path, depth, folder: true, count: e.count });
          if (all || open.has(e.path)) walk(e.children, depth + 1);
        }
      }
    };
    walk(root, 0);
    return out;
  });
  readonly allOpen = computed(() => this.s().keys().length <= 40);

  constructor() {
    effect(() => {
      const s = this.s();
      if (!s.loaded) {
        s.loaded = true;
        untracked(() => this.load(true));
      }
    });
  }

  async load(fresh: boolean): Promise<void> {
    const s = this.s();
    if (fresh) {
      s.keys.set([]);
      s.cursor.set('0');
      s.done.set(false);
    }
    s.loading.set(true);
    // SCAN walks the keyspace in steps; a few steps fill one page even when the pattern matches few keys.
    let cursor = s.cursor();
    let found: KeyInfoView[] = [];
    for (let i = 0; i < 20; i++) {
      const r = await this.engine.keys(this.spec().service, s.pattern(), cursor);
      if (!r.ok) {
        s.error.set(r.error);
        s.loading.set(false);
        return;
      }
      found = found.concat(r.value.keys);
      cursor = r.value.next;
      if (cursor === '0' || found.length >= 500) break;
    }
    s.error.set(null);
    const seen = new Set(s.keys().map((k) => k.key));
    s.keys.update((k) => [...k, ...found.filter((x) => !seen.has(x.key))]);
    s.cursor.set(cursor);
    s.done.set(cursor === '0');
    s.loading.set(false);
  }

  /** Step 1 of deleting by pattern: count the keys and show some of them. Nothing is deleted yet. */
  async previewDelete(): Promise<void> {
    const pattern = this.s().pattern();
    const r = await this.engine.matching(this.spec().service, pattern);
    if (!r.ok) {
      this.s().error.set(r.error);
      return;
    }
    this.s().bulk.set({ pattern, ...r.value });
  }

  /** Step 2: the user saw the count and confirmed. Deletes exactly the pattern that was previewed. */
  async deleteMatching(): Promise<void> {
    const b = this.s().bulk();
    if (!b) return;
    this.s().bulk.set(null);
    const r = await this.engine.deleteMatching(this.spec().service, b.pattern);
    if (!r.ok) {
      this.s().error.set(r.error);
      return;
    }
    this.toast.emit(`Deleted ${r.value.deleted.toLocaleString()} keys matching ${b.pattern}`);
    this.s().selected.set(null);
    this.s().value.set(null);
    this.load(true);
  }

  search(pattern: string): void {
    const p = pattern.trim() || '*';
    const d = this.doc();
    const s = this.s();
    s.bulk.set(null);
    if (d.spec.kind !== 'redis' || p === s.pattern()) return void this.load(true);
    s.pattern.set(p);
    d.spec = { ...d.spec, pattern: p };
    this.wb.rename(d.id, `${p} · ${d.spec.service}`);
    s.loaded = true;
    this.load(true);
  }

  toggle(path: string): void {
    this.s().open.update((o) => {
      const next = new Set(o);
      next.has(path) ? next.delete(path) : next.add(path);
      return next;
    });
  }

  async pick(key: string): Promise<void> {
    const s = this.s();
    s.selected.set(key);
    s.deleting.set(false);
    const r = await this.engine.key(this.spec().service, key);
    if (s.selected() !== key) return; // another key was picked meanwhile
    s.value.set(r.ok ? r.value : null);
    s.valueError.set(r.ok ? null : r.error);
  }

  reloadValue(): void {
    const k = this.s().selected();
    if (k) this.pick(k);
  }

  async setTtl(raw: string): Promise<void> {
    const key = this.s().selected();
    if (!key) return;
    const seconds = raw.trim() === '' ? null : Number(raw);
    if (seconds !== null && (!Number.isFinite(seconds) || seconds <= 0)) {
      this.s().valueError.set('A TTL is a number of seconds above 0; leave it empty for "never expires".');
      return;
    }
    const r = await this.engine.expireKey(this.spec().service, key, seconds);
    if (!r.ok) this.s().valueError.set(r.error);
    else this.toast.emit(seconds === null ? `${key} never expires now` : `${key} expires in ${seconds} s`);
    this.reloadValue();
  }

  async remove(): Promise<void> {
    const s = this.s();
    const key = s.selected();
    if (!key) return;
    if (!s.deleting()) {
      s.deleting.set(true); // asks first: a deleted key is gone
      return;
    }
    const r = await this.engine.deleteKey(this.spec().service, key);
    s.deleting.set(false);
    if (!r.ok) {
      s.valueError.set(r.error);
      return;
    }
    this.toast.emit(`Deleted ${key}`);
    s.keys.update((k) => k.filter((x) => x.key !== key));
    s.selected.set(null);
    s.value.set(null);
  }

  // ---- keys to a file and back ----

  async exportKeys(): Promise<void> {
    const pattern = this.s().pattern() || '*';
    const path = await savePath(`${this.spec().service}-keys.json`, ['json']);
    if (path === null) return;
    const r = await this.engine.exportKeys(this.spec().service, pattern, path);
    if (!r.ok) return this.toast.emit(r.error);
    if (r.value.content !== undefined) download(`${this.spec().service}-keys.json`, r.value.content);
    this.toast.emit(`Saved ${r.value.keys} key${r.value.keys === 1 ? '' : 's'} matching ${pattern}`);
  }

  async importKeys(): Promise<void> {
    const f = await openFile(['json']);
    if (!f) return;
    const replace = confirm('Overwrite keys that already exist?\n\nOK: overwrite them. Cancel: keep them and skip those in the file.');
    const r = await this.engine.importKeys(this.spec().service, 'content' in f ? { content: f.content } : { path: f.path }, replace);
    if (!r.ok) return this.toast.emit(r.error);
    this.toast.emit(`Put back ${r.value.written} key${r.value.written === 1 ? '' : 's'}${r.value.skipped ? `, kept ${r.value.skipped} existing` : ''}`);
    await this.load(true);
  }

  // ---- editing values: each change is sent right away, like in redis-cli, and the value reloads ----

  async apply(edit: RedisEdit, done?: string): Promise<boolean> {
    const s = this.s();
    const key = s.selected();
    if (!key) return false;
    const r = await this.engine.editKey(this.spec().service, key, edit);
    s.editing.set(null);
    s.armed.set(null);
    if (!r.ok) {
      s.valueError.set(r.error);
      return false;
    }
    s.valueError.set(null);
    if (done) this.toast.emit(done);
    await this.pick(key);
    return true;
  }

  /** Strings and JSON documents can be edited when the whole value was loaded. */
  canEditText(v: KeyValueView): boolean {
    return v.text !== undefined && (v.type === 'ReJSON-RL' || v.text.length >= v.length);
  }

  saveText(v: KeyValueView, text: string): void {
    if (v.type === 'ReJSON-RL') {
      try {
        JSON.parse(text);
      } catch (e) {
        this.s().valueError.set(`Not valid JSON: ${(e as Error).message}`);
        return;
      }
      this.apply({ op: 'set-json', value: text }, 'Saved');
    } else this.apply({ op: 'set-text', value: text }, 'Saved');
  }

  /** First click arms, second click removes. */
  remove2(id: string, edit: RedisEdit): void {
    const s = this.s();
    if (s.armed() !== id) {
      s.armed.set(id);
      setTimeout(() => s.armed() === id && s.armed.set(null), 3000);
      return;
    }
    this.apply(edit);
  }

  /** A new key gets its first value right away: Redis has no empty keys. */
  async create(name: string, type: string, a: string, b: string): Promise<void> {
    const s = this.s();
    if (!name.trim()) return;
    const exists = await this.engine.key(this.spec().service, name);
    if (exists.ok) {
      s.valueError.set(`${name} already exists (${exists.value.type}). Pick it on the left to change it.`);
      return;
    }
    const edit: RedisEdit | null =
      type === 'string' ? { op: 'set-text', value: a }
      : type === 'hash' ? { op: 'hash-set', field: a, value: b }
      : type === 'list' ? { op: 'list-push', value: a, head: false }
      : type === 'set' ? { op: 'set-add', member: a }
      : type === 'zset' ? { op: 'score-set', member: a, score: Number(b) || 0 }
      : null;
    if (!edit) return;
    const r = await this.engine.editKey(this.spec().service, name, edit);
    if (!r.ok) {
      s.valueError.set(r.error);
      return;
    }
    s.creating.set(false);
    this.toast.emit(`Created ${name}`);
    await this.load(true);
    await this.pick(name);
  }

  // ---- command box ----

  private historyAt = -1;

  async run(line: string, confirmed = false): Promise<void> {
    const s = this.s();
    if (!line.trim()) return;
    s.confirm.set(null);
    const r = await this.engine.redisCommand(this.spec().service, line, confirmed);
    if (r.ok && r.value.confirm) {
      s.confirm.set({ line, why: r.value.confirm });
      return;
    }
    s.typed.unshift(line);
    this.historyAt = -1;
    const reply = r.ok ? (r.value.reply ?? '') : r.error;
    s.log.update((l) => [...l.slice(-49), { line, reply, error: !r.ok || reply.startsWith('(error)') }]);
    const el = this.cmd()?.nativeElement;
    if (el) el.value = '';
    queueMicrotask(() => document.querySelector('.log-end')?.scrollIntoView({ block: 'nearest' }));
    this.reloadValue(); // the command may have changed the key on screen
  }

  cmdKey(e: KeyboardEvent, el: HTMLInputElement): void {
    const typed = this.s().typed;
    if (e.key === 'Enter') this.run(el.value);
    else if (e.key === 'ArrowUp' && typed.length) {
      this.historyAt = Math.min(this.historyAt + 1, typed.length - 1);
      el.value = typed[this.historyAt];
      e.preventDefault();
    } else if (e.key === 'ArrowDown') {
      this.historyAt = Math.max(this.historyAt - 1, -1);
      el.value = this.historyAt < 0 ? '' : typed[this.historyAt];
      e.preventDefault();
    }
  }

  // ---- display helpers ----

  typeClass = (t: string) => TYPE_COLORS[t] ?? 'oth';

  ttl(seconds: number | null): string {
    if (seconds === null) return '∞';
    if (seconds < 60) return `${Math.round(seconds)}s`;
    if (seconds < 3600) return `${Math.round(seconds / 60)}m`;
    if (seconds < 86400) return `${Math.round(seconds / 3600)}h`;
    return `${Math.round(seconds / 86400)}d`;
  }

  /** JSON strings are shown indented when "pretty" is on; anything else as is. */
  text(v: KeyValueView): string {
    if (!this.s().pretty() || v.text === undefined) return v.text ?? '';
    const t = v.text.trim();
    if (!(t.startsWith('{') || t.startsWith('['))) return v.text;
    try {
      return JSON.stringify(JSON.parse(t), null, 2);
    } catch {
      return v.text;
    }
  }

  shown(v: KeyValueView): number {
    return v.items?.length ?? v.fields?.length ?? v.scored?.length ?? v.entries?.length ?? (v.text?.length ?? 0);
  }

  copy(text: string): void {
    navigator.clipboard?.writeText(text);
    this.toast.emit('Copied');
  }
}
