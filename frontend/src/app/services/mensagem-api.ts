import { HttpErrorResponse } from '@angular/common/http';

/**
 * Texto de erro que a API mandou pra mostrar ao usuario (`{"error": "..."}`, ver TratadorErrosApi
 * no backend), ou o texto padrao da tela. So usa a mensagem da API em 4xx: nesses casos ela diz o
 * que a pessoa precisa corrigir; em 5xx/rede o padrao da tela e mais util.
 */
export function mensagemDaApi(erro: unknown, padrao: string): string {
  if (erro instanceof HttpErrorResponse && erro.status >= 400 && erro.status < 500) {
    const texto = (erro.error as { error?: unknown } | null)?.error;
    if (typeof texto === 'string' && texto.trim()) return texto;
  }
  return padrao;
}
