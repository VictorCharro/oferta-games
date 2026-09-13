import { HttpInterceptorFn } from '@angular/common/http';
import { InjectionToken, inject } from '@angular/core';
import { URL_API } from './url-api';

/**
 * Segredo que o SSR manda pra API em `X-SSR-Token` (issue #21).
 *
 * O SSR da Vercel chama a API a partir de poucos IPs, e o limite por IP do backend fazia todos os
 * visitantes dividirem o mesmo balde: com trafego real, o SSR recebia 429 e renderizava erro. Com o
 * token, o backend reconhece o SSR e usa um balde proprio (ver FiltroLimiteRequisicoes).
 *
 * So e PROVIDO no AppServerModule, lido de `process.env.SSR_API_TOKEN` na function da Vercel. No
 * navegador o token nao existe (injecao opcional devolve null) e o interceptor nao faz nada — o
 * segredo nunca vai pro bundle do browser.
 */
export const TOKEN_SSR_API = new InjectionToken<string>('TOKEN_SSR_API');

export const tokenSsrInterceptor: HttpInterceptorFn = (requisicao, proximo) => {
  const token = inject(TOKEN_SSR_API, { optional: true });
  // So pra nossa API: o token nao pode vazar pra terceiros (ITAD, Steam, Supabase).
  if (!token || !requisicao.url.startsWith(URL_API)) return proximo(requisicao);
  return proximo(requisicao.clone({ setHeaders: { 'X-SSR-Token': token } }));
};
