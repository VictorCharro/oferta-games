import { Injectable, RESPONSE_INIT, inject } from '@angular/core';

/**
 * Status HTTP da resposta do SSR.
 *
 * Sem isto toda pagina renderizada saia com 200, inclusive "Jogo nao encontrado" e a tela de erro
 * de carregamento. Dois problemas:
 * - o Google indexa "nao encontrado" como pagina valida (soft 404);
 * - com cache na CDN (ver app.routes.server.ts), uma falha momentanea da API virava uma pagina de
 *   erro congelada por minutos pra todo mundo. A CDN da Vercel nao guarda resposta 5xx, entao
 *   marcar a falha como 503 e o que impede isso.
 *
 * No navegador `RESPONSE_INIT` nao existe (so e provido na renderizacao do servidor), e a chamada
 * vira no-op.
 */
@Injectable({ providedIn: 'root' })
export class StatusResposta {
  private readonly resposta = inject(RESPONSE_INIT, { optional: true });

  naoEncontrado() {
    this.definir(404);
  }

  indisponivel() {
    this.definir(503);
  }

  private definir(status: number) {
    if (this.resposta) this.resposta.status = status;
  }
}
