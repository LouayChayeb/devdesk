import { AfterViewInit, Directive, ElementRef, inject } from '@angular/core';

/** Focuses (and selects) an input as soon as it appears, e.g. a grid cell switching to edit mode. */
@Directive({ selector: '[ddAutofocus]' })
export class Autofocus implements AfterViewInit {
  private readonly el = inject<ElementRef<HTMLInputElement>>(ElementRef);

  ngAfterViewInit(): void {
    this.el.nativeElement.focus();
    this.el.nativeElement.select?.();
  }
}
