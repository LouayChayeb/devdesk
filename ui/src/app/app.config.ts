import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';

/** Angular's app-wide setup: only the global error listener (the engine connection lives in engine.ts). */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
  ]
};
