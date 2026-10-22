import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { BucketListing, Engine, FilePreview } from './engine';
import { Doc } from './workbench';
import { Icon } from './icon';
import { bytes } from './format';
import { pickAnyFile, saveAnyPath } from './files';

/**
 * One bucket of S3-style storage (MinIO, RustFS): folders and files, a preview of the picked file, and the explicit
 * changes (upload, download, delete). The engine talks S3; this only shows and asks.
 */
@Component({
  selector: 'dd-bucket-view',
  imports: [Icon],
  template: `
    <div class="bar">
      <span class="crumbs mono">
        <a (click)="go('')">{{ bucket() }}</a>
        @for (c of crumbs(); track c.prefix) { <span class="dim">/</span><a (click)="go(c.prefix)">{{ c.name }}</a> }
      </span>
      <span class="grow"></span>
      <button class="sm" (click)="upload()" title="Upload a file into this folder"><dd-icon name="upload" [size]="12" /> Upload</button>
      <button class="ghost icon sm" title="Reload" (click)="load()"><dd-icon name="restart" [size]="13" /></button>
    </div>
    @if (error(); as e) { <p class="problem small">{{ e }}</p> }
    <div class="split">
      <div class="list">
        @if (listing(); as l) {
          @if (prefix()) {
            <button class="row" (click)="up()"><dd-icon name="folder" [size]="13" /><span class="name mono">..</span></button>
          }
          @for (f of l.folders; track f) {
            <button class="row" (click)="go(f)"><dd-icon name="folder" [size]="13" /><span class="name mono">{{ short(f) }}</span></button>
          }
          @for (f of l.files; track f.key) {
            <button class="row" [class.on]="picked() === f.key" (click)="pick(f.key)">
              <dd-icon name="logs" [size]="13" /><span class="name mono">{{ short(f.key) }}</span>
              <span class="dim small mono">{{ size(f.size) }}</span>
              <span class="dim small mono when">{{ when(f.modified) }}</span>
            </button>
          }
          @if (!l.folders.length && !l.files.length) { <p class="dim small pad">Empty. Upload a file, or let the app write some.</p> }
          @if (l.next) { <button class="ghost sm more" (click)="more()">More files…</button> }
        } @else {
          <p class="dim small pad">Reading…</p>
        }
      </div>
      <div class="preview">
        @if (picked(); as key) {
          <div class="phead">
            <span class="mono name" [title]="key">{{ key }}</span>
            <span class="grow"></span>
            <button class="sm" (click)="download(key)"><dd-icon name="download" [size]="12" /> Download</button>
            <button class="ghost icon sm" title="Delete this file" (click)="remove(key)"><dd-icon name="trash" [size]="13" /></button>
          </div>
          @if (preview(); as p) {
            <div class="dim small mono">{{ p.contentType }} · {{ size(p.size) }}{{ p.truncated ? ' · showing the start' : '' }}</div>
            @if (p.image) {
              <img [src]="'data:' + p.contentType + ';base64,' + p.image" alt="" />
            } @else if (p.text !== null) {
              <pre class="mono">{{ p.text }}</pre>
            } @else {
              <p class="dim small">Not text or a small image: download it to open it.</p>
            }
          } @else {
            <p class="dim small">Reading…</p>
          }
        } @else {
          <p class="dim small">Pick a file to see its start.</p>
        }
      </div>
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; padding: 10px 14px; gap: 8px; }
    .bar { display: flex; align-items: center; gap: 8px; }
    .crumbs a { cursor: pointer; color: var(--accent); }
    .crumbs .dim { margin: 0 3px; }
    .grow { flex: 1; }
    .split { flex: 1; display: flex; gap: 12px; min-height: 0; }
    .list { flex: 1; overflow: auto; min-width: 0; border: 1px solid var(--border); border-radius: var(--radius); background: var(--bg-1); }
    .row { display: flex; width: 100%; height: auto; align-items: center; gap: 8px; justify-content: flex-start; padding: 4px 10px; border: none; border-radius: 0; background: transparent; text-align: left; }
    .row:hover { background: var(--bg-2); }
    .row.on { background: var(--accent-weak); }
    .name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--fs-sm); }
    .when { width: 120px; text-align: right; }
    .pad { padding: 10px; }
    .more { margin: 6px 10px; }
    .preview { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; overflow: auto; }
    .phead { display: flex; align-items: center; gap: 6px; }
    pre { margin: 0; flex: 1; overflow: auto; font-size: var(--fs-sm); background: var(--bg-0); border: 1px solid var(--border); border-radius: var(--radius-badge); padding: 8px 10px; white-space: pre-wrap; word-break: break-word; }
    img { max-width: 100%; max-height: 60vh; object-fit: contain; align-self: flex-start; border: 1px solid var(--border); border-radius: var(--radius-badge); background: var(--bg-0); }
    .problem { background: var(--err-bg); border-left: 2px solid var(--err); padding: 6px 10px; border-radius: var(--radius-badge); margin: 0; }
  `,
})
export class BucketView {
  readonly engine = inject(Engine);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();

  readonly prefix = signal('');
  readonly listing = signal<BucketListing | null>(null);
  readonly picked = signal<string | null>(null);
  readonly preview = signal<FilePreview | null>(null);
  readonly error = signal<string | null>(null);

  readonly service = computed(() => (this.doc().spec.kind === 'bucket' ? (this.doc().spec as { service: string }).service : ''));
  readonly bucket = computed(() => (this.doc().spec.kind === 'bucket' ? (this.doc().spec as { bucket: string }).bucket : ''));
  /** "invoices/2026/" → invoices, 2026: each one a link back to that folder. */
  readonly crumbs = computed(() => {
    const parts = this.prefix().split('/').filter(Boolean);
    return parts.map((name, i) => ({ name, prefix: parts.slice(0, i + 1).join('/') + '/' }));
  });

  constructor() {
    effect(() => {
      this.doc();
      untracked(() => this.load());
    });
  }

  async load(): Promise<void> {
    this.error.set(null);
    const r = await this.engine.bucketFiles(this.service(), this.bucket(), this.prefix());
    if (r.ok) this.listing.set(r.value);
    else this.error.set(r.error);
  }

  async more(): Promise<void> {
    const l = this.listing();
    if (!l?.next) return;
    const r = await this.engine.bucketFiles(this.service(), this.bucket(), this.prefix(), l.next);
    if (r.ok) this.listing.set({ folders: [...l.folders, ...r.value.folders], files: [...l.files, ...r.value.files], next: r.value.next });
    else this.error.set(r.error);
  }

  go(prefix: string): void {
    this.prefix.set(prefix);
    this.picked.set(null);
    this.listing.set(null);
    this.load();
  }

  up(): void {
    const parts = this.prefix().split('/').filter(Boolean);
    this.go(parts.length > 1 ? parts.slice(0, -1).join('/') + '/' : '');
  }

  async pick(key: string): Promise<void> {
    this.picked.set(key);
    this.preview.set(null);
    const r = await this.engine.filePreview(this.service(), this.bucket(), key);
    if (r.ok && this.picked() === key) this.preview.set(r.value);
    else if (!r.ok) this.error.set(r.error);
  }

  async upload(): Promise<void> {
    const path = await pickAnyFile();
    if (path === null) {
      if (!('__TAURI_INTERNALS__' in window)) this.toast.emit('Uploading needs the desktop app (its file dialog).');
      return;
    }
    const r = await this.engine.uploadFile(this.service(), this.bucket(), this.prefix(), path);
    if (!r.ok) return this.error.set(r.error);
    this.toast.emit(`Uploaded ${r.value.key} (${bytes(r.value.bytes)})`);
    await this.load();
  }

  async download(key: string): Promise<void> {
    const path = await saveAnyPath(this.short(key));
    if (path === null) {
      if (!('__TAURI_INTERNALS__' in window)) this.toast.emit('Downloading needs the desktop app (its file dialog).');
      return;
    }
    const r = await this.engine.downloadFile(this.service(), this.bucket(), key, path);
    if (r.ok) this.toast.emit(`Saved ${r.value.saved}`);
    else this.error.set(r.error);
  }

  async remove(key: string): Promise<void> {
    if (!confirm(`Delete ${key} from ${this.bucket()}? It can't be undone.`)) return;
    const r = await this.engine.deleteFile(this.service(), this.bucket(), key);
    if (!r.ok) return this.error.set(r.error);
    this.picked.set(null);
    await this.load();
  }

  /** The last part of a key or folder: "invoices/2026/a.pdf" → "a.pdf", "invoices/2026/" → "2026/". */
  short(key: string): string {
    const trimmed = key.endsWith('/') ? key.slice(0, -1) : key;
    return trimmed.slice(trimmed.lastIndexOf('/') + 1) + (key.endsWith('/') ? '/' : '');
  }

  size(n: number): string {
    return bytes(n);
  }

  when(iso: string): string {
    const d = new Date(iso);
    return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' }) + ' ' + d.toLocaleTimeString('en-GB', { hour12: false, hour: '2-digit', minute: '2-digit' });
  }
}
