/** Display helpers only: turning engine values into text and CSS classes. No decisions about state. */

export type Tone = 'ok' | 'ok-ring' | 'warn' | 'warn-ring' | 'err' | 'err-x' | 'idle';

/** DESIGN.md §2 "Status visuals": colour always comes with a distinct shape and a label. */
export function tone(label: string): Tone {
  switch (label) {
    case 'healthy':
    case 'completed':
      return 'ok';
    case 'running':
      return 'ok-ring';
    case 'starting':
    case 'waiting':
    case 'stopping':
      return 'warn';
    case 'blocked':
      return 'warn-ring';
    case 'unhealthy':
    case 'failed':
      return 'err';
    case 'crashed':
    case 'crash-loop':
      return 'err-x';
    default:
      return 'idle';
  }
}

export function bytes(n: number | null | undefined): string {
  if (n == null) return '–';
  if (n < 1024) return `${Math.round(n)} B`; // keys and mails are often tiny: "464 B", not "0K"
  if (n < 1024 * 1024) return `${Math.round(n / 1024)}K`;
  if (n < 1024 * 1024 * 1024) return `${Math.round(n / 1024 / 1024)}M`;
  return `${(n / 1024 / 1024 / 1024).toFixed(1)}G`;
}

export function cpu(n: number | null | undefined): string {
  return n == null ? '–' : `${n < 10 ? n.toFixed(1) : Math.round(n)}%`;
}

/** The engine sends UTC timestamps; show them in the user's own time zone. */
export function time(iso: string): string {
  const d = new Date(iso);
  return isNaN(d.getTime()) ? iso : d.toLocaleTimeString('en-GB', { hour12: false });
}

/** One coloured piece of a log line. */
export interface Segment {
  text: string;
  cls: string;
}

const ANSI = /\x1b\[([0-9;]*)m/g;

/** Renders the common ANSI colour codes (FORCE_COLOR=1 makes tools emit them) and drops everything else. */
export function ansi(line: string): Segment[] {
  const out: Segment[] = [];
  let cls = '';
  let last = 0;
  for (const m of line.matchAll(ANSI)) {
    if (m.index! > last) out.push({ text: line.slice(last, m.index), cls });
    for (const code of (m[1] || '0').split(';').map(Number)) {
      if (code === 0 || code === 39) cls = '';
      else if (code === 1) cls = `${cls} b`;
      else if ((code >= 30 && code <= 37) || (code >= 90 && code <= 97)) cls = `a${code}`;
    }
    last = m.index! + m[0].length;
  }
  if (last < line.length) out.push({ text: line.slice(last), cls });
  return out.map((s) => ({ ...s, text: s.text.replace(/\x1b\[[0-9;?]*[A-Za-z]/g, '') }));
}
