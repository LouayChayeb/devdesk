import { Component, ElementRef, computed, inject, input, OnInit, output, signal, viewChild } from '@angular/core';
import { Engine, Finding, SetupDraft, SetupPreview, SetupScan, SetupService } from './engine';
import { Icon } from './icon';
import { StatusDot } from './status-dot';
import { SetupCanvas } from './setup-canvas';

const STEPS = ['Scan', 'Services', 'Dependencies', 'Settings', 'Test run', 'Done'] as const;
const NAME = /^[a-z0-9][a-z0-9_-]*$/;
/** Same rule as the engine's ServiceEnvironment.looksSecret: these values are hidden until clicked. */
const SECRET = /SECRET|PASSW|TOKEN|KEY|PRIVATE|CREDENTIAL|AUTH|SESSION|COOKIE|DSN|DATABASE_URL/i;
const UP = ['healthy', 'running', 'completed'];

/**
 * The "Add project" wizard: shown when a folder has no devdesk.yaml, or from "Edit setup". The engine scans, checks,
 * test-runs and writes; this component only shows the draft and sends the user's edits back.
 */
@Component({
  selector: 'dd-setup-wizard',
  imports: [Icon, StatusDot, SetupCanvas],
  templateUrl: './setup-wizard.html',
  styleUrl: './setup-wizard.css',
})
export class SetupWizard implements OnInit {
  readonly engine = inject(Engine);
  readonly path = input.required<string>();
  /** The project folder once devdesk.yaml is written (the app opens it). */
  readonly done = output<string>();
  readonly cancel = output<void>();

  readonly steps = STEPS;
  private readonly body = viewChild<ElementRef<HTMLElement>>('body');
  readonly step = signal(0);
  readonly scan = signal<SetupScan | null>(null);
  readonly draft = signal<SetupDraft>({ name: '', envFiles: [], services: [], notes: [] });
  readonly suggestions = signal<SetupService[]>([]);
  readonly preview = signal<SetupPreview | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly dbMenu = signal(false);
  /** Step 3 as boxes and arrows, or as tick boxes (keyboard friendly). */
  readonly depsView = signal<'canvas' | 'list'>('canvas');
  readonly revealed = signal<Set<string>>(new Set());
  readonly newValues = signal<Record<string, string>>({});
  /** The doctor's report on the draft: what this machine lacks to run it (versions, Docker, packages…). */
  readonly doctor = signal<{ problems: Finding[]; passed: string[]; skipped?: string } | null>(null);
  readonly showPassed = signal(false);
  /** Set once the wizard started a test run: Cancel and Done stop it again. */
  readonly tested = signal(false);

  readonly editing = computed(() => this.scan()?.mode === 'edit');
  readonly hasDoctorErrors = computed(() => !!this.doctor()?.problems.some((f) => f.severity === 'error'));
  readonly names = computed(() => this.draft().services.map((s) => s.name));
  /** Problems the user can fix right on the cards, before asking the engine. */
  readonly cardProblems = computed(() => {
    const seen = new Set<string>();
    return this.draft().services.map((s) => {
      const p: string[] = [];
      if (!NAME.test(s.name)) p.push('Name: lowercase letters, digits, - and _ (like "my-api").');
      else if (seen.has(s.name)) p.push(`Another service is already called "${s.name}".`);
      seen.add(s.name);
      if (s.kind === 'command' && !s.command.trim()) p.push('Type the command that starts it.');
      if (s.kind === 'container' && !s.image.trim()) p.push('Choose an image, like postgres:18.');
      return p;
    });
  });
  readonly canContinue = computed(() => {
    if (this.step() === 1) return this.draft().services.length > 0 && this.cardProblems().every((p) => p.length === 0);
    if (this.step() === 4) return !this.busy();
    return !this.busy() && this.scan() !== null;
  });
  /** The test run's services, as the engine reports them (only once the open project is this draft). */
  readonly testRows = computed(() => {
    const p = this.engine.project();
    if (!this.tested() || !p || p.root !== this.scan()?.path) return [];
    return p.services.map((s) => ({
      name: s.name,
      label: s.state.label,
      detail: s.state.detail,
      ok: UP.includes(s.state.label),
      finding: (this.engine.findings()[s.name] ?? [])[0] ?? null,
    }));
  });
  readonly testOutcome = computed(() => {
    const rows = this.testRows();
    if (!rows.length) return 'idle';
    if (rows.every((r) => r.ok)) return 'ok';
    if (rows.some((r) => ['failed', 'crashed', 'blocked', 'unhealthy'].includes(r.label))) return 'failed';
    return 'running';
  });

  async ngOnInit(): Promise<void> {
    this.busy.set(true);
    const r = await this.engine.setupScan(this.path());
    this.busy.set(false);
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    this.scan.set(r.value);
    this.draft.set(r.value.draft);
    this.suggestions.set(r.value.suggestions);
    await this.runDoctor();
  }

  async runDoctor(): Promise<void> {
    const r = await this.engine.setupDoctor(this.scan()!.path, this.draft());
    this.doctor.set(r.ok ? r.value : null);
  }

  // ---- navigation ----

  async go(to: number): Promise<void> {
    if (to < 0 || to >= STEPS.length || this.busy()) return;
    if (to > this.step() && !this.canContinue()) return;
    this.error.set(null);
    this.dbMenu.set(false);
    this.step.set(to);
    if (to >= 3) await this.refreshPreview();
    if (to === 4) await this.runDoctor(); // the test run shows what would make it fail before it starts
  }

  async refreshPreview(): Promise<void> {
    const r = await this.engine.setupPreview(this.scan()!.path, this.draft());
    if (r.ok) this.preview.set(r.value);
    else this.error.set(r.error);
  }

  // ---- cards ----

  setName(name: string): void {
    this.draft.update((d) => ({ ...d, name }));
  }

  patch(i: number, change: Partial<SetupService>): void {
    this.draft.update((d) => {
      const old = d.services[i];
      const next = { ...old, ...change };
      // A renamed service keeps its place in the others' "needs" lists.
      const services = d.services.map((s, j) => {
        if (j === i) return next;
        if (change.name === undefined || !(old.name in s.dependsOn)) return s;
        const deps: Record<string, string> = {};
        for (const [k, v] of Object.entries(s.dependsOn)) deps[k === old.name ? change.name : k] = v;
        return { ...s, dependsOn: deps };
      });
      return { ...d, services };
    });
  }

  remove(i: number): void {
    this.draft.update((d) => {
      const gone = d.services[i].name;
      const services = d.services
        .filter((_, j) => j !== i)
        .map((s) => {
          const { [gone]: _removed, ...deps } = s.dependsOn;
          return { ...s, dependsOn: deps };
        });
      return { ...d, services };
    });
  }

  addBlank(): void {
    const taken = new Set(this.names());
    let name = 'app';
    for (let n = 2; taken.has(name); n++) name = `app-${n}`;
    this.draft.update((d) => ({
      ...d,
      services: [
        ...d.services,
        {
          name, kind: 'command', command: '', directory: '', image: '', ports: [], volumes: [], environment: {},
          dependsOn: {}, health: { type: 'process', target: '', startPeriod: '' }, restart: 'on-failure',
          origin: 'added by you', notes: [], extras: {},
        },
      ],
    }));
    this.showLastCard();
  }

  /** The menu's words for the engine's template kinds. */
  kindLabel(kind: string): string {
    return (
      {
        postgres: 'Postgres',
        mysql: 'MySQL',
        redis: 'Redis',
        mongo: 'MongoDB',
        mailpit: 'Mailpit (catches e-mail)',
        rabbitmq: 'RabbitMQ',
        kafka: 'Kafka',
        elasticsearch: 'Elasticsearch',
        s3: 'S3 storage (local, RustFS)',
        sqs: 'SQS queues (local, ElasticMQ)',
        localstack: 'AWS offline (LocalStack)',
      } as Record<string, string>
    )[kind] ?? kind;
  }

  async addDatabase(kind: string): Promise<void> {
    this.dbMenu.set(false);
    const r = await this.engine.setupDatabase(kind, this.draft());
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    this.draft.update((d) => ({ ...d, services: [...d.services, r.value] }));
    this.showLastCard();
  }

  /** A new card lands at the bottom of the list: bring it into view. */
  private showLastCard(): void {
    setTimeout(() => this.body()?.nativeElement.querySelector('.card:last-of-type')?.scrollIntoView({ behavior: 'smooth' }));
  }

  takeSuggestion(s: SetupService): void {
    const taken = new Set(this.names());
    let name = s.name;
    for (let n = 2; taken.has(name); n++) name = `${s.name}-${n}`;
    this.draft.update((d) => ({ ...d, services: [...d.services, { ...s, name }] }));
    this.suggestions.update((list) => list.filter((x) => x !== s));
  }

  /** The engine switches the card: it knows how a built service runs on this machine, and back. */
  async setKind(i: number, kind: 'command' | 'container'): Promise<void> {
    if (this.draft().services[i].kind === kind) return;
    const r = await this.engine.setupSwitch(this.draft(), i, kind);
    if (r.ok) this.patch(i, r.value);
  }

  list(text: string): string[] {
    return text.split(/[,\s]+/).map((p) => p.trim()).filter(Boolean);
  }

  envRows(s: SetupService): [string, string][] {
    return Object.entries(s.environment);
  }

  setEnv(i: number, oldKey: string, key: string, value: string): void {
    const env: Record<string, string> = {};
    for (const [k, v] of Object.entries(this.draft().services[i].environment)) {
      if (k === oldKey) env[key] = value;
      else env[k] = v;
    }
    this.patch(i, { environment: env });
  }

  addEnv(i: number): void {
    const env = { ...this.draft().services[i].environment };
    let key = 'NEW_VARIABLE';
    for (let n = 2; key in env; n++) key = `NEW_VARIABLE_${n}`;
    env[key] = '';
    this.patch(i, { environment: env });
  }

  removeEnv(i: number, key: string): void {
    const { [key]: _gone, ...env } = this.draft().services[i].environment;
    this.patch(i, { environment: env });
  }

  isSecret(key: string): boolean {
    return SECRET.test(key);
  }

  shown(id: string): boolean {
    return this.revealed().has(id);
  }

  toggleShown(id: string): void {
    this.revealed.update((set) => {
      const next = new Set(set);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  healthHint(type: string): string {
    return type === 'tcp' ? 'port, like 3000' : type === 'http' ? 'http://127.0.0.1:3000/health' : type === 'command' ? 'a command that exits 0 when ready' : '';
  }

  // ---- dependencies ----

  needs(s: SetupService, other: string): boolean {
    return other in s.dependsOn;
  }

  toggleNeed(i: number, other: string): void {
    const deps = { ...this.draft().services[i].dependsOn };
    if (other in deps) delete deps[other];
    else deps[other] = this.isDataStore(other) ? 'healthy' : 'started';
    this.patch(i, { dependsOn: deps });
  }

  setNeeds(service: string, dependsOn: Record<string, string>): void {
    const i = this.draft().services.findIndex((s) => s.name === service);
    if (i >= 0) this.patch(i, { dependsOn });
  }

  setCondition(i: number, other: string, condition: string): void {
    this.patch(i, { dependsOn: { ...this.draft().services[i].dependsOn, [other]: condition } });
  }

  private isDataStore(name: string): boolean {
    return !!this.draft().services.find((x) => x.name === name)?.dataStore;
  }

  // ---- settings ----

  setNewValue(key: string, value: string): void {
    this.newValues.update((v) => ({ ...v, [key]: value }));
  }

  async saveVariable(key: string): Promise<void> {
    const file = this.draft().envFiles[0] ?? '.env';
    const r = await this.engine.setupVariable(this.scan()!.path, file, key, this.newValues()[key] ?? '');
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    if (!this.draft().envFiles.includes(file)) this.draft.update((d) => ({ ...d, envFiles: [...d.envFiles, file] }));
    // A card's own empty value would win over the env file: drop it so the saved value is the one used.
    this.draft.update((d) => ({
      ...d,
      services: d.services.map((s) => {
        if (s.environment[key] !== '') return s;
        const { [key]: _empty, ...environment } = s.environment;
        return { ...s, environment };
      }),
    }));
    await this.refreshPreview();
  }

  // ---- test run ----

  async runTest(): Promise<void> {
    this.error.set(null);
    this.busy.set(true);
    const r = await this.engine.setupTest(this.scan()!.path, this.draft());
    this.busy.set(false);
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    this.tested.set(true);
  }

  async stopTest(): Promise<void> {
    this.busy.set(true);
    await this.engine.down();
    await this.untilStopped();
    this.busy.set(false);
  }

  /** The engine stops services in the background; wait for its events to say everything stopped. */
  private async untilStopped(): Promise<void> {
    for (let i = 0; i < 120 && this.engine.running(); i++) await new Promise((r) => setTimeout(r, 250));
  }

  // ---- finish ----

  async write(): Promise<void> {
    this.error.set(null);
    this.busy.set(true);
    if (this.tested() && this.engine.running()) {
      await this.engine.down();
      await this.untilStopped();
    }
    const replace = this.editing() || !!this.preview()?.exists;
    if (replace && !this.editing() && !confirm('A devdesk.yaml appeared in this folder meanwhile. Replace it?')) {
      this.busy.set(false);
      return;
    }
    const r = await this.engine.setupWrite(this.scan()!.path, this.draft(), replace);
    this.busy.set(false);
    if (!r.ok) {
      this.error.set(r.error);
      return;
    }
    this.done.emit(this.scan()!.path);
  }

  async close(): Promise<void> {
    if (this.tested()) {
      this.busy.set(true);
      if (this.engine.running()) {
        await this.engine.down();
        await this.untilStopped();
      }
      // A test-run draft is open in the engine: close it, or put the saved file back for "Edit setup".
      if (this.editing()) await this.engine.open(this.scan()!.path);
      else await this.engine.setupDiscard();
      this.busy.set(false);
    }
    this.cancel.emit();
  }
}
