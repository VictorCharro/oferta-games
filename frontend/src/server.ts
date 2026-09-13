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

/**
 * CSP em duas camadas (issue #30).
 *
 * APLICADA: so diretivas que nao dependem do que a pagina carrega, entao nao tem como quebrar a
 * renderizacao — sem <object>/<embed>, sem <base> apontando pra fora, sem enviar formulario pra
 * outro site e sem ser embutido em iframe.
 *
 * REPORT-ONLY: a politica completa que o site deveria seguir. O navegador so AVISA no console o que
 * ela bloquearia, sem bloquear. Estilos e o script inline do tema (index.html) exigem
 * 'unsafe-inline' hoje; imagens vem de varios CDNs (ITAD, Steam, Xbox, Supabase), por isso https:.
 * Quando o console ficar limpo em producao, da pra promover diretivas daqui pra aplicada.
 */
const CSP_APLICADA = "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";
const CSP_RELATORIO = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
  "font-src 'self' data: https://fonts.gstatic.com",
  "img-src 'self' data: blob: https:",
  "media-src 'self' blob: https:",
  "connect-src 'self' https://api.163.176.220.243.sslip.io https://*.supabase.co wss://*.supabase.co https:",
  "frame-src https://www.youtube.com https://www.youtube-nocookie.com",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join('; ');

// Headers de seguranca em toda resposta renderizada pela function (HTML do SSR).
app.use((_req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
  res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
  res.setHeader('Content-Security-Policy', CSP_APLICADA);
  res.setHeader('Content-Security-Policy-Report-Only', CSP_RELATORIO);
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
