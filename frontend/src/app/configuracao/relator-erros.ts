import { ErrorHandler, Injectable, PLATFORM_ID, inject } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { URL_API } from './url-api';

/**
 * Manda pro backend os erros nao tratados do navegador (issue #29), que aparecem na aba "Erros" do
 * admin. Antes, uma excecao no navegador de um usuario era invisivel.
 *
 * Continua escrevendo no console como o ErrorHandler padrao. Com provideBrowserGlobalErrorListeners,
 * erros fora do Angular (window.onerror, promise rejeitada) tambem chegam aqui.
 *
 * Fica de fora: erro HTTP (o backend registra os proprios 500; 4xx e rede caindo nao sao bug) e
 * SSR (so roda no navegador). Freios: o mesmo erro vai uma vez por pagina carregada e no maximo 10
 * relatos, pra um erro em loop nao virar enxurrada.
 */
@Injectable()
export class RelatorErros implements ErrorHandler {
  private readonly noNavegador = isPlatformBrowser(inject(PLATFORM_ID));
  private readonly enviados = new Set<string>();

  handleError(erro: unknown): void {
    console.error(erro);
    if (!this.noNavegador || erro instanceof HttpErrorResponse) return;
    try {
      const real = (erro as { rejection?: unknown })?.rejection ?? erro;
      if (real instanceof HttpErrorResponse) return;
      const mensagem = real instanceof Error ? `${real.name}: ${real.message}` : String(real);
      if (this.enviados.has(mensagem) || this.enviados.size >= 10) return;
      this.enviados.add(mensagem);
      const corpo = JSON.stringify({
        mensagem: mensagem.slice(0, 500),
        pilha: real instanceof Error ? (real.stack ?? '').slice(0, 4000) : null,
        pagina: location.pathname.slice(0, 300),
      });
      // keepalive: o relato sai mesmo se o erro acontecer bem na hora de trocar de pagina.
      fetch(`${URL_API}/erros`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: corpo, keepalive: true })
        .catch(() => undefined);
    } catch {
      // Relatar erro nunca pode gerar outro erro.
    }
  }
}
