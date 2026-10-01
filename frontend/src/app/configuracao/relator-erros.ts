import { ErrorHandler, Injectable, PLATFORM_ID, inject, isDevMode } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { URL_API } from './url-api';
import { ehChunkDesatualizado, recarregouHaPouco } from './versao-nova';

/**
 * O erro nasceu numa extensao do navegador do visitante (tradutor, bloqueador, gerenciador de
 * senha...), nao no site. Olha so a PRIMEIRA URL da pilha: e onde o erro foi lancado. Se o nosso
 * codigo chamou algo que a extensao interceptou, a extensao aparece depois e o erro continua sendo
 * relatado. Caso real (26/09/2026): "Cannot read properties of undefined (reading 'M_ID')" em
 * chrome-extension://.../executors/200.js, sem nenhuma linha do site na pilha.
 */
export function vemDeExtensao(pilha: string | null | undefined): boolean {
  const primeiraUrl = pilha?.match(/[a-z][a-z0-9+.-]*:\/\/[^\s)]+/i)?.[0] ?? '';
  return /^(chrome|moz|safari-web|ms-browser)-extension:\/\//i.test(primeiraUrl);
}

/**
 * Manda pro backend os erros nao tratados do navegador (issue #29), que aparecem na aba "Erros" do
 * admin. Antes, uma excecao no navegador de um usuario era invisivel.
 *
 * Continua escrevendo no console como o ErrorHandler padrao. Com provideBrowserGlobalErrorListeners,
 * erros fora do Angular (window.onerror, promise rejeitada) tambem chegam aqui.
 *
 * Fica de fora: erro HTTP (o backend registra os proprios 500; 4xx e rede caindo nao sao bug),
 * erro lancado por extensao do navegador (vemDeExtensao), chunk de versao antiga que a recarga
 * resolve (versao-nova.ts) e SSR (so roda no navegador). Tambem nao relata em modo de desenvolvimento (`ng serve`): o localhost
 * aponta pra API de producao, e um erro de teste local ia parar na aba Erros do admin como se fosse
 * de um usuario (aconteceu em 26/09/2026). Em dev o erro continua no console. Freios: o mesmo erro vai uma vez por pagina carregada e no maximo 10
 * relatos, pra um erro em loop nao virar enxurrada.
 */
@Injectable()
export class RelatorErros implements ErrorHandler {
  private readonly noNavegador = isPlatformBrowser(inject(PLATFORM_ID));
  private readonly relatar = !isDevMode();
  private readonly enviados = new Set<string>();

  handleError(erro: unknown): void {
    console.error(erro);
    if (!this.noNavegador || !this.relatar || erro instanceof HttpErrorResponse) return;
    try {
      const real = (erro as { rejection?: unknown })?.rejection ?? erro;
      if (real instanceof HttpErrorResponse) return;
      if (real instanceof Error && vemDeExtensao(real.stack)) return;
      // Chunk de versao antiga: o App ja recarrega na versao nova. So relata se o erro voltou
      // logo depois dessa recarga, porque ai recarregar nao resolveu (ver versao-nova.ts).
      if (ehChunkDesatualizado(real) && !recarregouHaPouco()) return;
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
