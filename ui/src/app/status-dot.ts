import { Component, computed, input } from '@angular/core';
import { tone } from './format';

/** The status dot from DESIGN.md: solid, ring, pulsing or crossed, never colour alone. */
@Component({
  selector: 'dd-dot',
  template: `<span class="dot {{ cls() }}" [attr.aria-label]="label()"></span>`,
  styles: `
    .dot { display: inline-block; width: 8px; height: 8px; border-radius: 999px; flex: none; position: relative; }
    .ok { background: var(--ok); }
    .ok-ring { border: 2px solid var(--ok); }
    .warn { background: var(--warn); animation: pulse 1.4s ease-in-out infinite; }
    .warn-ring { border: 2px dashed var(--warn); }
    .err { background: var(--err); }
    .err-x { background: var(--err); }
    .err-x::after { content: '×'; position: absolute; inset: -5px 0 0 0; font: 600 10px/1 var(--font-ui); color: var(--bg-0); text-align: center; top: -1px; }
    .idle { background: var(--idle); }
    @keyframes pulse { 50% { opacity: 0.35; } }
  `,
})
export class StatusDot {
  readonly label = input.required<string>();
  readonly cls = computed(() => tone(this.label()));
}
