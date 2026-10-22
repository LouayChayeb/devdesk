import { Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { Engine, MailPage, MailView as Mail } from './engine';
import { Doc, Workbench } from './workbench';
import { RequestState } from './request-view';
import { Icon } from './icon';
import { bytes } from './format';
import { savePath } from './files';

/** One inbox tab: the list, the open mail, and what's being searched. */
class MailState {
  readonly search = signal('');
  readonly page = signal<MailPage | null>(null);
  readonly openId = signal<string | null>(null);
  readonly mail = signal<Mail | null>(null);
  readonly show = signal<'html' | 'text' | 'headers'>('html');
  readonly error = signal<string | null>(null);
}

/** The e-mails the app sent to the project's Mailpit: an inbox on the left, the open mail on the right. */
@Component({
  selector: 'dd-mail-view',
  imports: [Icon],
  template: `
    @let st = s();
    <div class="bar">
      <dd-icon name="mail" [size]="14" />
      <input class="mono find" placeholder="Search: words, to:ada, subject:order, is:unread…" [value]="st.search()" (input)="st.search.set($any($event.target).value)" (keydown.enter)="load()" />
      <button class="ghost sm" (click)="load()" title="Read the inbox again (it also refreshes by itself)"><dd-icon name="restart" [size]="12" /> Refresh</button>
      <span class="count mono">
        @if (st.page(); as p) { {{ p.total }} mail{{ p.total === 1 ? '' : 's' }} · {{ p.unread }} unread }
      </span>
      <button class="ghost sm danger-text" (click)="clear()" [disabled]="!st.page()?.total" title="Delete every mail in this Mailpit"><dd-icon name="trash" [size]="12" /> Delete all</button>
    </div>
    @if (st.error(); as e) {
      <div class="err small pad">{{ e }}</div>
    }
    <div class="split">
      <div class="list">
        @for (m of st.page()?.mails ?? []; track m.id) {
          <div class="row" [class.on]="m.id === st.openId()" [class.unread]="!m.read" (click)="open(m.id)">
            <div class="l1">
              <span class="from">{{ m.from }}</span>
              <span class="at mono">{{ when(m.at) }}</span>
            </div>
            <div class="subj">
              @if (m.attachments) { <dd-icon name="bookmark" [size]="11" /> }
              {{ m.subject || '(no subject)' }}
            </div>
            <div class="snip dim">to {{ m.to.join(', ') }} · {{ m.snippet }}</div>
          </div>
        } @empty {
          @if (st.page()) {
            <div class="none small">
              <p>No mail yet.</p>
              <p class="dim">When the app sends an e-mail to this Mailpit's SMTP port, it shows up here within a few seconds.</p>
            </div>
          }
        }
      </div>
      <div class="reader">
        @if (st.mail(); as m) {
          <div class="head">
            <div class="subject">{{ m.subject || '(no subject)' }}</div>
            <div class="meta"><span class="k">From</span><span class="mono">{{ m.from }}</span></div>
            <div class="meta"><span class="k">To</span><span class="mono">{{ m.to.join(', ') }}</span></div>
            @if (m.cc.length) { <div class="meta"><span class="k">Cc</span><span class="mono">{{ m.cc.join(', ') }}</span></div> }
            <div class="meta"><span class="k">Date</span><span class="mono">{{ full(m.at) }} · {{ size(m.size) }}</span></div>
            <button class="ghost icon sm del" title="Delete this mail" (click)="remove(m.id)"><dd-icon name="trash" [size]="13" /></button>
          </div>
          @if (m.attachments.length) {
            <div class="files">
              @for (a of m.attachments; track a.partId) {
                <button class="sm" (click)="saveFile(m.id, a)" [title]="'Save ' + a.name + ' (' + a.contentType + ')'"><dd-icon name="download" [size]="12" /> {{ a.name || 'attachment' }} <span class="dim">{{ size(a.size) }}</span></button>
              }
            </div>
          }
          @if (m.links.length) {
            <div class="links">
              <div class="label">Links in this mail</div>
              @for (l of m.links; track l.url) {
                <div class="link">
                  @if (l.service) { <span class="svc mono">{{ l.service }}</span> } @else { <span class="svc ext mono">outside</span> }
                  <span class="url mono" [title]="l.url">{{ l.url }}</span>
                  @if (l.service) {
                    <button class="sm" (click)="asRequest(l.request)" [title]="'Open GET ' + l.request + ' in the API client'"><dd-icon name="send" [size]="11" /> Open as request</button>
                  }
                  <button class="ghost icon sm" (click)="copy(l.url)" title="Copy the link"><dd-icon name="copy" [size]="12" /></button>
                </div>
              }
            </div>
          }
          <div class="tabs segmented small-seg">
            <button [class.on]="shown() === 'html'" [disabled]="!m.html" (click)="st.show.set('html')" [title]="m.html ? 'The mail as the reader sees it' : 'This mail has no HTML part'">HTML</button>
            <button [class.on]="shown() === 'text'" (click)="st.show.set('text')">Text</button>
            <button [class.on]="shown() === 'headers'" (click)="st.show.set('headers')">Headers <span class="n">{{ m.headers.length }}</span></button>
          </div>
          @switch (shown()) {
            @case ('html') {
              <!-- No scripts, no same-origin access, no navigation: a mail can only be looked at. -->
              <iframe class="body" sandbox="" [srcdoc]="html()" title="The mail as HTML"></iframe>
            }
            @case ('text') { <pre class="body text mono">{{ m.text || '(no text part)' }}</pre> }
            @case ('headers') {
              <div class="body hdrs mono">
                @for (h of m.headers; track $index) { <div><span class="hn">{{ h.name }}:</span> {{ h.value }}</div> }
              </div>
            }
          }
        } @else {
          <div class="none"><dd-icon name="mail" [size]="26" /><p class="dim">Pick a mail on the left.</p></div>
        }
      </div>
    </div>
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-height: 0; }
    .bar { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--border); color: var(--text-3); }
    .find { flex: 1; max-width: 460px; }
    .count { margin-left: auto; font-size: var(--fs-xs); }
    .danger-text { color: var(--err); }
    .pad { padding: 8px 12px; }
    .split { flex: 1; display: grid; grid-template-columns: minmax(280px, 380px) 1fr; min-height: 0; }
    .list { overflow: auto; border-right: 1px solid var(--border); background: var(--bg-1); }
    .row { padding: 8px 12px; border-bottom: 1px solid var(--border); cursor: pointer; display: grid; gap: 2px; }
    .row:hover { background: var(--bg-2); }
    .row.on { background: var(--accent-weak); }
    .l1 { display: flex; gap: 8px; font-size: var(--fs-sm); color: var(--text-2); }
    .from { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .at { color: var(--text-3); font-size: var(--fs-xs); }
    .subj { color: var(--text-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: flex; gap: 6px; align-items: center; }
    .unread .subj { font-weight: 600; }
    .unread .l1::before { content: ''; width: 6px; height: 6px; border-radius: 50%; background: var(--accent); align-self: center; }
    .snip { font-size: var(--fs-xs); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .reader { display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: auto; }
    .head { position: relative; padding: 12px 16px 10px; border-bottom: 1px solid var(--border); display: grid; gap: 3px; }
    .subject { font-size: 16px; font-weight: 600; color: var(--text-1); margin-bottom: 4px; padding-right: 32px; }
    .meta { display: flex; gap: 10px; font-size: var(--fs-sm); color: var(--text-2); }
    .k { width: 40px; color: var(--text-3); flex: none; }
    .del { position: absolute; right: 10px; top: 10px; }
    .files { display: flex; flex-wrap: wrap; gap: 6px; padding: 8px 16px; border-bottom: 1px solid var(--border); }
    .links { padding: 8px 16px; border-bottom: 1px solid var(--border); display: grid; gap: 4px; }
    .label { font: 500 var(--fs-xs) var(--font-ui); color: var(--text-3); text-transform: uppercase; letter-spacing: 0.06em; }
    .link { display: flex; align-items: center; gap: 8px; min-width: 0; }
    .svc { font-size: var(--fs-xs); color: var(--accent); background: var(--accent-weak); padding: 0 6px; border-radius: 3px; flex: none; }
    .svc.ext { color: var(--text-3); background: var(--bg-2); }
    .url { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--fs-sm); color: var(--text-2); }
    .tabs { margin: 8px 16px 0; align-self: flex-start; }
    .n { color: var(--text-3); margin-left: 4px; }
    .body { flex: 1; min-height: 240px; margin: 8px 16px 16px; border: 1px solid var(--border); border-radius: var(--radius); }
    iframe.body { background: #fff; }
    .text { white-space: pre-wrap; padding: 12px; margin-top: 8px; font-size: var(--fs-md); overflow: auto; }
    .hdrs { padding: 10px 12px; font-size: var(--fs-sm); overflow: auto; white-space: pre-wrap; word-break: break-all; }
    .hn { color: var(--accent); }
    .none { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; flex: 1; padding: 24px; text-align: center; color: var(--text-3); }
  `,
})
export class MailView {
  readonly engine = inject(Engine);
  readonly wb = inject(Workbench);
  private readonly sanitizer = inject(DomSanitizer);
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();
  readonly show = output<Doc>();

  readonly s = computed(() => {
    const d = this.doc();
    if (!(d.state instanceof MailState)) d.state = new MailState();
    return d.state as MailState;
  });
  readonly service = computed(() => (this.doc().spec as { service: string }).service);
  /** A mail without HTML shows its text, whatever tab was picked last. */
  readonly shown = computed(() => {
    const m = this.s().mail();
    const v = this.s().show();
    return v === 'html' && !m?.html ? 'text' : v;
  });
  /** The sandboxed iframe above is what makes this safe: the mail's HTML can't run anything. */
  readonly html = computed<SafeHtml>(() => this.sanitizer.bypassSecurityTrustHtml(this.s().mail()?.html ?? ''));

  constructor() {
    effect(() => {
      void this.doc();
      untracked(() => this.load());
    });
    // New mail shows up by itself: the app sends it in the background (a worker, a queue), not when you click.
    const timer = setInterval(() => this.load(true), 3000);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  async load(quiet = false): Promise<void> {
    const st = this.s();
    const r = await this.engine.mails(this.service(), st.search());
    if (!r.ok) {
      if (!quiet || !st.page()) st.error.set(r.error);
      return;
    }
    st.error.set(null);
    st.page.set(r.value);
    if (!st.openId() && r.value.mails.length) await this.open(r.value.mails[0].id);
  }

  async open(id: string): Promise<void> {
    const st = this.s();
    st.openId.set(id);
    const r = await this.engine.mail(this.service(), id);
    if (!r.ok) {
      st.error.set(r.error);
      return;
    }
    st.mail.set(r.value);
    // Reading it marks it read in Mailpit: show that in the list without waiting for the next refresh.
    st.page.update((p) => (p ? { ...p, mails: p.mails.map((m) => (m.id === id && !m.read ? { ...m, read: true } : m)), unread: p.mails.some((m) => m.id === id && !m.read) ? p.unread - 1 : p.unread } : p));
  }

  async remove(id: string): Promise<void> {
    const r = await this.engine.deleteMails(this.service(), [id]);
    if (!r.ok) return this.toast.emit(r.error);
    const st = this.s();
    st.mail.set(null);
    st.openId.set(null);
    await this.load();
  }

  async clear(): Promise<void> {
    const total = this.s().page()?.total ?? 0;
    if (!confirm(`Delete all ${total} mails in ${this.service()}? This can't be undone.`)) return;
    const r = await this.engine.deleteMails(this.service(), []);
    if (!r.ok) return this.toast.emit(r.error);
    const st = this.s();
    st.mail.set(null);
    st.openId.set(null);
    await this.load();
    this.toast.emit(`Deleted ${total} mails`);
  }

  /** A link to one of the project's services opens as a GET request, with {{service.url}} so it follows the port. */
  asRequest(url: string): void {
    const doc = this.wb.open({ kind: 'request', requestId: null });
    doc.state = new RequestState({ method: 'GET', url });
    this.wb.rename(doc.id, `From mail · GET`);
    this.show.emit(doc);
  }

  async saveFile(id: string, a: Mail['attachments'][number]): Promise<void> {
    const path = await savePath(a.name || 'attachment', [a.name.split('.').pop() || 'bin']);
    if (path === null) return;
    if (path === '') {
      // A plain browser: no save dialog, so the page downloads the bytes itself.
      const blob = await this.engine.attachmentBlob(this.service(), id, a.partId);
      if (!blob) return this.toast.emit(`Couldn't read ${a.name}`);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = a.name || 'attachment';
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      return;
    }
    const r = await this.engine.saveAttachment(this.service(), id, a.partId, path);
    this.toast.emit(r.ok ? `Saved ${r.value.path}` : r.error);
  }

  copy(text: string): void {
    navigator.clipboard?.writeText(text);
    this.toast.emit('Link copied');
  }

  when(iso: string): string {
    const d = new Date(iso);
    const today = new Date().toDateString() === d.toDateString();
    return today ? d.toLocaleTimeString('en-GB', { hour12: false }) : d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short' });
  }

  full(iso: string): string {
    return new Date(iso).toLocaleString('en-GB', { hour12: false });
  }

  readonly size = bytes;
}
