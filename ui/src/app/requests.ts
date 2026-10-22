import { Injectable, inject, signal } from '@angular/core';
import { CookieView, Engine, HttpOverview, SavedRequestView } from './engine';

/** The API client's saved requests, variables and history, as the engine keeps them for the open project. */
@Injectable({ providedIn: 'root' })
export class Requests {
  private readonly engine = inject(Engine);
  readonly saved = signal<SavedRequestView[]>([]);
  readonly variables = signal<HttpOverview['variables']>([]);
  readonly history = signal<HttpOverview['history']>([]);
  readonly cookies = signal<CookieView[]>([]);
  readonly shared = signal<string[]>([]);

  async refresh(): Promise<void> {
    const o = await this.engine.httpOverview();
    if (o) this.set(o);
  }

  set(o: HttpOverview): void {
    this.saved.set(o.saved);
    this.variables.set(o.variables);
    this.history.set(o.history);
    this.cookies.set(o.cookies ?? []);
    this.shared.set(o.shared ?? []);
  }
}
