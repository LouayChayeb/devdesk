import { SlowView } from './slow-view';
import { BucketView } from './bucket-view';
import { IndexView } from './index-view';
import { QueueView } from './queue-view';
import { RecorderView } from './recorder-view';
import { StressViewComponent } from './stress-view';
import { Component, input, output } from '@angular/core';
import { Doc } from './workbench';
import { TableView } from './table-view';
import { SqlView } from './sql-view';
import { RedisTools } from './redis-tools';
import { ActivityView } from './activity-view';
import { BackupsView } from './backups-view';
import { RedisView } from './redis-view';
import { RequestView } from './request-view';
import { RunViewComponent } from './run-view';
import { MailView } from './mail-view';
import { DiagramViewComponent } from './diagram-view';
import { StatsViewComponent } from './stats-view';
import { DesignViewComponent } from './design-view';
import { MockViewComponent } from './mock-view';
import { MongoViewComponent } from './mongo-view';

/** Any Workbench tab's view, by its kind: used for the main pane and for the right half of a split. */
@Component({
  selector: 'dd-doc-view',
  imports: [TableView, SqlView, RedisTools, ActivityView, BackupsView, RedisView, RequestView, RunViewComponent, MailView, DiagramViewComponent, StatsViewComponent, DesignViewComponent, MockViewComponent, MongoViewComponent, SlowView, BucketView, IndexView, QueueView, RecorderView, StressViewComponent],
  template: `
    @let d = doc();
    @switch (d.spec.kind) {
      @case ('table') { <dd-table-view [doc]="d" (toast)="toast.emit($event)" (show)="show.emit($event)" /> }
      @case ('design') { <dd-design-view [doc]="d" (toast)="toast.emit($event)" (show)="show.emit($event)" /> }
      @case ('sql') { <dd-sql-view [doc]="d" (toast)="toast.emit($event)" /> }
      @case ('redis-tools') { <dd-redis-tools [doc]="d" (toast)="toast.emit($event)" /> }
      @case ('activity') { <dd-activity-view [doc]="d" (toast)="toast.emit($event)" /> }
      @case ('backups') { <dd-backups-view [doc]="d" (toast)="toast.emit($event)" /> }
      @case ('redis') { <dd-redis-view [doc]="d" (toast)="toast.emit($event)" /> }
      @case ('run') { <dd-run-view [doc]="d" (show)="show.emit($event)" /> }
      @case ('request') { <dd-request-view [doc]="d" (toast)="toast.emit($event)" (variables)="variables.emit()" (logs)="logs.emit($event)" (show)="show.emit($event)" /> }
      @case ('mongo') { <dd-mongo-view [doc]="d" (toast)="toast.emit($event)" /> }
      @case ('mock') { <dd-mock-view [doc]="d" (toast)="toast.emit($event)" /> }
      @case ('stress') { <dd-stress-view [doc]="d" /> }
      @case ('recorder') { <dd-recorder-view [doc]="d" (toast)="toast.emit($event)" /> }
      @case ('queues') { <dd-queue-view [doc]="d" (toast)="toast.emit($event)" /> }
      @case ('bucket') { <dd-bucket-view [doc]="d" (toast)="toast.emit($event)" /> }
      @case ('index') { <dd-index-view [doc]="d" /> }
      @case ('slow') { <dd-slow-view [doc]="d" (toast)="toast.emit($event)" /> }
      @case ('stats') { <dd-stats-view [doc]="d" (show)="show.emit($event)" /> }
      @case ('diagram') { <dd-diagram-view [doc]="d" (show)="show.emit($event)" /> }
      @case ('mail') { <dd-mail-view [doc]="d" (toast)="toast.emit($event)" (show)="show.emit($event)" /> }
    }
  `,
  styles: `
    :host { display: flex; flex-direction: column; min-width: 0; min-height: 0; }
    :host > * { flex: 1; min-height: 0; }
  `,
})
export class DocView {
  readonly doc = input.required<Doc>();
  readonly toast = output<string>();
  readonly show = output<Doc>();
  readonly variables = output<void>();
  readonly logs = output<string>();
}
