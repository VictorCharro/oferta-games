import { NgModule } from '@angular/core';
import { provideServerRendering, withRoutes } from '@angular/ssr';
import { App } from './app';
import { AppModule } from './app-module';
import { serverRoutes } from './app.routes.server';
import { TOKEN_SSR_API } from './configuracao/token-ssr';

// `process` so existe no Node (a function da Vercel); o tipo nao entra no tsconfig do app, entao le
// pelo globalThis. Variavel ausente vira string vazia e o interceptor simplesmente nao manda nada.
const ambiente = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env ?? {};

@NgModule({
  imports: [AppModule],
  providers: [
    provideServerRendering(withRoutes(serverRoutes)),
    { provide: TOKEN_SSR_API, useValue: ambiente['SSR_API_TOKEN']?.trim() ?? '' },
  ],
  bootstrap: [App],
})
export class AppServerModule {}
