import { Component, ElementRef, computed, inject, output, signal, viewChild, AfterViewInit } from '@angular/core';
import { Engine } from './engine';
import { Catalog } from './catalog';
import { DocSpec, PAGES } from './workbench';
import { Requests } from './requests';

/** What the app does when a palette entry is picked. The app turns these into the same engine calls as the buttons. */
export type PaletteCommand =
  | { kind: 'service'; action: 'start' | 'stop' | 'restart'; service: string }
  | { kind: 'logs' | 'why' | 'details' | 'data'; service: string }
  | { kind: 'project'; action: 'up' | 'down' | 'check' | 'snapshots' }
  | { kind: 'tab'; tab: string }
  | { kind: 'open'; spec: DocSpec }
  | { kind: 'port'; port: string };

interface Entry {
  label: string;
  hint: string;
  command: PaletteCommand;
}

/** DESIGN.md §4.9: type a few letters of "restart api", "logs web" or "port 5432". Searching only; no decisions. */
@Component({
  selector: 'dd-palette',
  template: `
    <div class="scrim" (click)="close.emit()">
      <div class="box" (click)="$event.stopPropagation()" role="dialog" aria-label="Command palette">
        <input
          #q
          class="mono"
          placeholder="start api, logs web, port 5432, a table, a key, a request…"
          [value]="query()"
          (input)="query.set($any($event.target).value); index.set(0)"
          (keydown)="key($event)"
        />
        <div class="list">
          @for (e of matches(); track $index; let i = $index) {
            <div class="item" [class.on]="i === index()" (mouseenter)="index.set(i)" (click)="run(e)">
              <span class="label">{{ e.label }}</span><span class="hint">{{ e.hint }}</span>
            </div>
          } @empty {
            <div class="none">No match. Try "start", "logs", "port 3001", a service, a table or a request name.</div>
          }
        </div>
      </div>
    </div>
  `,
  styles: `
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); display: flex; justify-content: center; align-items: flex-start; padding-top: 12vh; z-index: 20; }
    .box { width: min(600px, 90vw); background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); overflow: hidden; }
    input { width: 100%; box-sizing: border-box; border: none; border-bottom: 1px solid var(--border); border-radius: 0; padding: 12px 14px; font-size: var(--fs-md); background: var(--bg-1); }
    input:focus { outline: none; }
    .list { max-height: 50vh; overflow: auto; padding: 4px; }
    .item { display: flex; justify-content: space-between; gap: 12px; padding: 7px 10px; border-radius: var(--radius); cursor: pointer; }
    .item.on { background: var(--bg-3); }
    .label { color: var(--text-1); }
    .hint { color: var(--text-3); font-size: var(--fs-sm); }
    .none { color: var(--text-3); padding: 10px; font-size: var(--fs-sm); }
  `,
})
export class CommandPalette implements AfterViewInit {
  readonly engine = inject(Engine);
  readonly catalog = inject(Catalog);
  readonly requests = inject(Requests);
  readonly pick = output<PaletteCommand>();
  readonly close = output<void>();
  readonly query = signal('');
  readonly index = signal(0);
  private readonly input = viewChild.required<ElementRef<HTMLInputElement>>('q');

  private readonly entries = computed<Entry[]>(() => {
    const p = this.engine.project();
    const out: Entry[] = [
      { label: 'start all', hint: 'start the project', command: { kind: 'project', action: 'up' } },
      { label: 'stop all', hint: 'stop the project', command: { kind: 'project', action: 'down' } },
      { label: 'check', hint: 'look for problems without starting', command: { kind: 'project', action: 'check' } },
      { label: 'snapshots', hint: 'save or restore all data', command: { kind: 'project', action: 'snapshots' } },
    ];
    for (const pg of PAGES) {
      out.push({ label: `go to ${pg.label.toLowerCase()}`, hint: pg.group.toLowerCase(), command: { kind: 'tab', tab: pg.id } });
    }
    // The Workbench: tables, Redis key browsers, saved requests. Opening one makes a tab.
    for (const d of this.catalog.databases()) {
      if (d.kind === 'redis') {
        out.push({ label: `keys ${d.service}`, hint: 'browse Redis keys', command: { kind: 'open', spec: { kind: 'redis', service: d.service, pattern: '*' } } });
        continue;
      }
      out.push({ label: `sql ${d.service}`, hint: 'new SQL query', command: { kind: 'open', spec: { kind: 'sql', service: d.service } } });
      for (const t of this.catalog.tables()[d.service] ?? []) {
        out.push({
          label: t.schema === 'public' ? t.name : `${t.schema}.${t.name}`,
          hint: `${t.view ? 'view' : 'table'} in ${d.service}`,
          command: { kind: 'open', spec: { kind: 'table', service: d.service, schema: t.schema, table: t.name } },
        });
      }
    }
    for (const r of this.requests.saved()) {
      out.push({ label: `${r.method} ${r.name}`, hint: `request${r.collection ? ' in ' + r.collection : ''}`, command: { kind: 'open', spec: { kind: 'request', requestId: r.id } } });
    }
    out.push({ label: 'new request', hint: 'API client', command: { kind: 'open', spec: { kind: 'request', requestId: null } } });
    for (const s of p?.services ?? []) {
      const n = s.name;
      out.push(
        { label: `start ${n}`, hint: 'and what it needs', command: { kind: 'service', action: 'start', service: n } },
        { label: `stop ${n}`, hint: 'and what needs it', command: { kind: 'service', action: 'stop', service: n } },
        { label: `restart ${n}`, hint: 'and what needs it', command: { kind: 'service', action: 'restart', service: n } },
        { label: `logs ${n}`, hint: 'show its output', command: { kind: 'logs', service: n } },
        { label: `why ${n}`, hint: 'explain what is wrong', command: { kind: 'why', service: n } },
        { label: `details ${n}`, hint: 'open the drawer', command: { kind: 'details', service: n } },
      );
      if (s.tools?.includes('data')) {
        out.push({ label: `data ${n}`, hint: 'tables, rows and SQL', command: { kind: 'data', service: n } });
      }
      for (const port of s.ports) {
        out.push({ label: `port ${port}`, hint: `declared by ${n}`, command: { kind: 'port', port: String(port) } });
      }
    }
    return out;
  });

  readonly matches = computed(() => {
    const q = this.query().trim().toLowerCase();
    const port = q.match(/^(?:port\s+)?(\d{1,5})$/);
    const found = q
      ? this.entries()
          .map((e) => ({ e, s: score(q, e.label) }))
          .filter((x) => x.s !== null)
          .sort((a, b) => a.s! - b.s!)
          .map((x) => x.e)
      : this.entries();
    if (port && !found.some((e) => e.label === `port ${port[1]}`)) {
      found.unshift({ label: `port ${port[1]}`, hint: 'who listens on it', command: { kind: 'port', port: port[1] } });
    }
    return found.slice(0, 50);
  });

  ngAfterViewInit(): void {
    this.input().nativeElement.focus();
  }

  key(e: KeyboardEvent): void {
    const n = this.matches().length;
    if (e.key === 'ArrowDown') this.index.set((this.index() + 1) % Math.max(n, 1));
    else if (e.key === 'ArrowUp') this.index.set((this.index() - 1 + n) % Math.max(n, 1));
    else if (e.key === 'Enter' && n > 0) this.run(this.matches()[this.index()]);
    else if (e.key === 'Escape') this.close.emit();
    else return;
    e.preventDefault();
  }

  run(e: Entry): void {
    this.pick.emit(e.command);
  }
}

/**
 * Lower is better, null means no match. Every typed letter must appear in order ("rsapi" → "restart api"); letters
 * at the start of a word and close together score best, so "rs" prefers "restart" over "go to services".
 */
function score(q: string, label: string): number | null {
  const want = q.replace(/\s+/g, '');
  const text = label.toLowerCase();
  if (!want) return 0;
  if (text.startsWith(q)) return -1000;
  let i = 0;
  let total = 0;
  let last = -1;
  for (let j = 0; j < text.length && i < want.length; j++) {
    if (text[j] !== want[i]) continue;
    const wordStart = j === 0 || text[j - 1] === ' ';
    total += (last < 0 ? j : j - last - 1) * 2 + (wordStart ? 0 : 3);
    last = j;
    i++;
  }
  return i === want.length ? total : null;
}
