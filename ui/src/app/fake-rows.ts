import { Component, OnInit, inject, input, output, signal } from '@angular/core';
import { Engine, FakePreview } from './engine';
import { Icon } from './icon';

/**
 * "Fill with test rows": shows how the engine will fill each column and a few example rows, then inserts the chosen
 * number in one transaction. The engine decides the values; this only shows and asks.
 */
@Component({
  selector: 'dd-fake-rows',
  imports: [Icon],
  template: `
    <div class="scrim" (click)="!busy() && close.emit()">
      <div class="panel" role="dialog" aria-label="Fake rows" (click)="$event.stopPropagation()">
        <header>
          <h3>Fill with test rows</h3>
          <span class="mono dim small">{{ schema() }}.{{ table() }}</span>
          <span class="grow"></span>
          <button class="ghost icon sm" title="Close" [disabled]="busy()" (click)="close.emit()"><dd-icon name="close" [size]="14" /></button>
        </header>

        @if (preview(); as p) {
          <table class="plan">
            @for (c of p.plan; track c.column) {
              <tr [class.skip]="!c.generated" [class.bad]="c.how.startsWith('can')">
                <td class="mono">{{ c.column }}</td>
                <td>{{ c.how }}</td>
              </tr>
            }
          </table>
          @if (p.problem) {
            <p class="problem small">Can't make rows yet: {{ p.problem }}</p>
          } @else if (p.examples.length) {
            <h4>Examples <button class="ghost sm" (click)="load()" title="Other examples"><dd-icon name="restart" [size]="11" /></button></h4>
            <div class="examples">
              <table class="mono small">
                <tr>@for (k of columns(p); track k) { <th>{{ k }}</th> }</tr>
                @for (r of p.examples; track $index) {
                  <tr>@for (k of columns(p); track k) { <td [class.null]="r[k] === null">{{ r[k] ?? 'NULL' }}</td> }</tr>
                }
              </table>
            </div>
          }
        } @else if (!error()) {
          <p class="dim small">Reading the table…</p>
        }
        @if (error(); as e) { <p class="problem small">{{ e }}</p> }
        @if (done(); as d) { <p class="ok small">✓ {{ d }}</p> }

        <footer>
          <label class="small">How many
            <input type="number" min="1" max="10000" [value]="count()" (input)="count.set(+$any($event.target).value)" />
          </label>
          <span class="grow dim small">All in one transaction: if one row is refused, none is added.</span>
          <button class="primary" [disabled]="busy() || !preview() || !!preview()?.problem" [class.busy]="busy()" (click)="insert()">
            <dd-icon name="plus" [size]="12" /> Add {{ count() }} rows
          </button>
        </footer>
      </div>
    </div>
  `,
  styles: `
    .scrim { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.55); display: flex; align-items: center; justify-content: center; z-index: 30; }
    .panel { width: min(900px, 94vw); max-height: 86vh; display: flex; flex-direction: column; gap: 10px; background: var(--bg-1); border: 1px solid var(--border-strong); border-radius: var(--radius); box-shadow: var(--shadow-float); padding: 16px 20px; overflow: auto; }
    header { display: flex; align-items: baseline; gap: 10px; }
    h3 { margin: 0; font: 700 var(--fs-xl) var(--font-display); }
    h4 { margin: 4px 0 0; font-size: var(--fs-xs); color: var(--text-2); text-transform: uppercase; letter-spacing: 0.04em; font-weight: 500; display: flex; align-items: center; gap: 6px; }
    .grow { flex: 1; }
    .plan { border-collapse: collapse; font-size: var(--fs-sm); }
    .plan td { padding: 3px 12px 3px 0; border-bottom: 1px solid var(--border); }
    .plan td:first-child { width: 200px; color: var(--text-1); }
    .plan tr.skip td { color: var(--text-3); }
    .plan tr.bad td { color: var(--err); }
    .examples { overflow: auto; border: 1px solid var(--border); border-radius: var(--radius-badge); background: var(--bg-0); }
    .examples table { border-collapse: collapse; }
    .examples th, .examples td { padding: 3px 10px; text-align: left; white-space: nowrap; border-bottom: 1px solid var(--border); max-width: 260px; overflow: hidden; text-overflow: ellipsis; }
    .examples th { color: var(--text-2); font-weight: 500; }
    .null { color: var(--text-3); font-style: italic; }
    footer { display: flex; align-items: center; gap: 10px; }
    footer input { width: 90px; height: 28px; margin-left: 6px; background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); color: var(--text-1); padding: 0 6px; }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); margin: 0; }
    .ok { color: var(--ok); margin: 0; }
  `,
})
export class FakeRows implements OnInit {
  readonly engine = inject(Engine);
  readonly service = input.required<string>();
  readonly schema = input.required<string>();
  readonly table = input.required<string>();
  readonly close = output<void>();
  readonly inserted = output<number>();

  readonly preview = signal<FakePreview | null>(null);
  readonly count = signal(50);
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly done = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  async load(): Promise<void> {
    const r = await this.engine.fakePreview(this.service(), this.schema(), this.table());
    if (r.ok) this.preview.set(r.value);
    else this.error.set(r.error);
  }

  columns(p: FakePreview): string[] {
    return p.plan.filter((c) => c.generated).map((c) => c.column);
  }

  async insert(): Promise<void> {
    const n = Math.min(Math.max(Math.floor(this.count()) || 1, 1), 10000);
    this.busy.set(true);
    this.error.set(null);
    this.done.set(null);
    const r = await this.engine.fakeInsert(this.service(), this.schema(), this.table(), n);
    this.busy.set(false);
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    this.done.set(`Added ${r.value.inserted} rows in ${(r.value.ms / 1000).toFixed(1)} s.`);
    this.inserted.emit(r.value.inserted);
  }
}
