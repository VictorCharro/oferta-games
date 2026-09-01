import {
  AngularNodeAppEngine,
  createNodeRequestHandler,
  isMainModule,
  writeResponseToNodeResponse,
} from '@angular/ssr/node';
import express from 'express';
import { join } from 'node:path';

const browserDistFolder = join(import.meta.dirname, '../browser');

const app = express();

/**
 * `trustProxyHeaders` e obrigatorio atras da edge da Vercel.
 *
 * Sem isso o engine ignora `X-Forwarded-Host`/`X-Forwarded-Proto`, nao consegue montar a URL real
 * da requisicao e devolve a casca CSR em vez de renderizar — silenciosamente, com HTTP 200. O
 * unico sinal e um warning no log da function ("Received x-forwarded-for header but
 * trustProxyHeaders was not set up to allow it").
 *
 * Confiar nesses headers e seguro aqui porque quem os define e a edge da Vercel, que descarta os
 * equivalentes vindos do cliente, e porque `security.allowedHosts` no angular.json restringe o
 * host aceito — e essa validacao que impede um Host forjado de virar SSRF.
 */
const angularApp = new AngularNodeAppEngine({ trustProxyHeaders: true });

// Headers de seguranca basicos em toda resposta. Sem CSP: os inline styles do Angular
// (component styles injetados no HTML) e as fontes do Google Fonts tornariam um CSP
// bem calibrado um projeto a parte; os headers abaixo ja cobrem o essencial sem risco de quebrar
// a renderizacao.
app.use((_req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  next();
});

/**
 * Example Express Rest API endpoints can be defined here.
 * Uncomment and define endpoints as necessary.
 *
 * Example:
 * ```ts
 * app.get('/api/{*splat}', (req, res) => {
 *   // Handle API request
 * });
 * ```
 */

/**
 * Serve static files from /browser
 */
app.use(
  express.static(browserDistFolder, {
    maxAge: '1y',
    index: false,
    redirect: false,
  }),
);

/**
 * Handle all other requests by rendering the Angular application.
 */
app.use((req, res, next) => {
  angularApp
    .handle(req)
    .then((response) =>
      response ? writeResponseToNodeResponse(response, res) : next(),
    )
    .catch(next);
});

/**
 * Start the server if this module is the main entry point, or it is ran via PM2.
 * The server listens on the port defined by the `PORT` environment variable, or defaults to 4000.
 */
if (isMainModule(import.meta.url) || process.env['pm_id']) {
  const port = process.env['PORT'] || 4000;
  app.listen(port, (error) => {
    if (error) {
      throw error;
    }

    console.log(`Node Express server listening on http://localhost:${port}`);
  });
}

/**
 * Request handler used by the Angular CLI (for dev-server and during build) or Firebase Cloud Functions.
 */
export const reqHandler = createNodeRequestHandler(app);
