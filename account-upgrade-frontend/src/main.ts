import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { appConfig } from './app/app.config';
import { loadRuntimeConfig } from './app/core/config/runtime-config';

// config.json is read before bootstrap so the backend URL can change per deployment without a rebuild.
loadRuntimeConfig()
  .then((runtimeConfig) => bootstrapApplication(App, appConfig(runtimeConfig)))
  .catch((err) => console.error(err));
