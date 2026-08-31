package com.ofertagames.backend.comum;

import java.time.Duration;
import org.springframework.http.CacheControl;

/**
 * Politica de cache HTTP das respostas publicas.
 *
 * <p>Sem {@code Cache-Control} nenhum — como era ate 31/08/2026 — nem o navegador nem a CDN da
 * Vercel podem reaproveitar resposta alguma: toda visita e todo "voltar pra home" refaz as
 * chamadas do zero.
 *
 * <p><b>So aplicar em resposta identica para todo mundo.</b> {@code public} autoriza cache
 * compartilhado, entao usar isso numa rota que varia por usuario faria a CDN entregar dado de um
 * usuario para outro. Rotas com {@code Authorization} — inclusive as de auth <i>opcional</i>, como
 * conquistas e reviews, que mudam de conteudo quando ha usuario logado — nunca podem usar.
 *
 * <p>Tambem nao aplicar onde o usuario espera ver efeito imediato de uma acao dele: o botao
 * "Atualizar precos" recarrega o detalhe do jogo logo apos o POST, e uma resposta cacheada faria a
 * atualizacao parecer que nao funcionou.
 */
public final class CacheHttp {
  /**
   * Cinco minutos, valor escolhido pra ser menor que o TTL do cache de servidor
   * ({@link ConfiguracaoCache}): o dado ja pode ter a idade do cache interno, e somar as duas
   * janelas define a defasagem maxima que o usuario ve.
   */
  private static final Duration VALIDADE = Duration.ofMinutes(5);

  private CacheHttp() {}

  /** Cacheavel por navegador e CDN. So para resposta que independe de quem pede. */
  public static CacheControl publico() {
    return CacheControl.maxAge(VALIDADE).cachePublic();
  }
}
