import { Component, computed, input } from '@angular/core';

/** Small line icons (24×24, 1.75 stroke), drawn inline so they stay crisp and follow the text colour. */
const PATHS: Record<string, string> = {
  download: 'M12 4v11M7 10l5 5 5-5M5 20h14',
  upload: 'M12 20V9M7 14l5-5 5 5M5 4h14',
  pulse: 'M3 12h4l3-7 4 14 3-7h4',
  archive: 'M4 5h16v4H4zM5 9v10h14V9M10 13h4',
  mail: 'M4 6h16v12H4zM4 7l8 6 8-6',
  bookmark: 'M7 4h10v16l-5-4-5 4z',
  split: 'M4 5h16v14H4zM12 5v14',
  plan: 'M5 5h6v4H5zM13 15h6v4h-6zM8 9v8h5',
  lock: 'M6 11h12v9H6zM9 11V8a3 3 0 0 1 6 0v3',
  radio: 'M12 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM8 8a6 6 0 0 0 0 8M16 8a6 6 0 0 1 0 8M5 5a10 10 0 0 0 0 14M19 5a10 10 0 0 1 0 14',
  filter: 'M4 5h16l-6 8v6l-4-2v-4z',
  play: 'M7 5v14l11-7z',
  stop: 'M7 7h10v10H7z',
  restart: 'M4 12a8 8 0 1 0 2.4-5.7M4 4v4h4',
  more: 'M5 12h.01M12 12h.01M19 12h.01',
  close: 'M6 6l12 12M18 6L6 18',
  check: 'M4 12l5 5L20 6',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4',
  services: 'M4 6h16M4 12h16M4 18h16',
  graph: 'M6 4h4v4H6zM14 16h4v4h-4zM14 4h4v4h-4zM8 8v4h8V8M16 12v4',
  ports: 'M5 9h14M5 15h14M9 5v14M15 5v14',
  diagnosis: 'M12 3l9 16H3zM12 10v4M12 17h.01',
  timeline: 'M12 7v5l3 2M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z',
  connections: 'M7 7h.01M17 17h.01M7 7l10 10M17 7h.01M7 17h.01',
  folder: 'M3 7h6l2 2h10v10H3z',
  plus: 'M12 5v14M5 12h14',
  shield: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z',
  external: 'M14 4h6v6M20 4l-9 9M18 14v6H4V6h6',
  fit: 'M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5',
  zoomIn: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4M8 11h6M11 8v6',
  zoomOut: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4M8 11h6',
  chevronDown: 'M6 9l6 6 6-6',
  chevronRight: 'M9 6l6 6-6 6',
  box: 'M4 8l8-4 8 4v8l-8 4-8-4zM4 8l8 4 8-4M12 12v8',
  terminal: 'M5 7l5 5-5 5M12 17h7',
  eye: 'M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  eyeOff: 'M3 3l18 18M10.6 6.1A10 10 0 0 1 12 6c6 0 10 6 10 6a17 17 0 0 1-3.2 3.8M6.6 6.6C3.8 8.4 2 12 2 12s4 7 10 7c1.6 0 3-.4 4.3-1',
  copy: 'M9 9h11v11H9zM5 15V4h11',
  database: 'M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3',
  send: 'M4 12l16-8-6 16-3-7zM11 13l9-9',
  key: 'M14 10a4 4 0 1 0-1.2 2.8L20 20M17 17l2-2M15 15l2-2',
  table: 'M4 5h16v14H4zM4 10h16M10 10v9',
  sql: 'M8 8l-4 4 4 4M16 8l4 4-4 4M13 6l-2 12',
  trash: 'M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13',
  undo: 'M9 7L4 12l5 5M4 12h11a5 5 0 0 1 0 10h-2',
  save: 'M5 4h11l3 3v13H5zM8 4v5h7V4M8 20v-6h8v6',
  edit: 'M4 20h4L19 9l-4-4L4 16zM13 7l4 4',
  menu: 'M4 7h16M4 12h16M4 17h16',
  logs: 'M4 5h16v14H4zM7 9h10M7 13h6',
  history: 'M3 12a9 9 0 1 0 3-6.7M3 4v5h5M12 8v4l3 2',
  star: 'M12 3l2.8 5.8 6.2.9-4.5 4.4 1 6.2L12 17.4 6.5 20.3l1-6.2L3 9.7l6.2-.9z',
  command: 'M9 6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3z',
};

@Component({
  selector: 'dd-icon',
  template: `<svg [attr.width]="size()" [attr.height]="size()" viewBox="0 0 24 24" aria-hidden="true">
    <path [attr.d]="d()" [class.fill]="filled()" />
  </svg>`,
  styles: `
    :host { display: inline-flex; flex: none; }
    path { fill: none; stroke: currentColor; stroke-width: 1.75; stroke-linecap: round; stroke-linejoin: round; }
    path.fill { fill: currentColor; stroke-width: 1; }
  `,
})
export class Icon {
  readonly name = input.required<string>();
  readonly size = input(16);
  readonly d = computed(() => PATHS[this.name()] ?? '');
  /** Play and stop read better as solid shapes. */
  readonly filled = computed(() => this.name() === 'play' || this.name() === 'stop');
}
