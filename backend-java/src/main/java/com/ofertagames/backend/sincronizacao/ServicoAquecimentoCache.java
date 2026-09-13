package com.ofertagames.backend.sincronizacao;

import com.ofertagames.backend.comum.ConfiguracaoCache;
import com.ofertagames.backend.descontos.RepositorioDescontos;
import com.ofertagames.backend.jogos.RepositorioJogos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Limpa e repopula o cache de catalogo/descontos logo apos cada rodada de precos — e uma vez no
 * boot.
 *
 * <p>Sem isso, o cache so seria populado pela primeira visita apos a expiracao, e quem chegasse
 * logo depois de uma sincronizacao pagaria a query pesada. Limpar antes de reaquecer tambem evita
 * servir preco velho de uma entrada que ainda nao expirou.
 *
 * <p>So aquece as combinacoes que Home, Catalogo, Mais Vendidos e Gratuitos usam de fato — e a
 * lista precisa ser mantida em sincronia com essas telas (e com {@code ParametrosPublicos}). Uma
 * combinacao nova no frontend que nao seja adicionada aqui simplesmente nunca sera aquecida, e quem
 * abrir aquela tela paga o custo frio.
 */
@Service
class ServicoAquecimentoCache {
  private static final Logger logger = LoggerFactory.getLogger(ServicoAquecimentoCache.class);

  private final CacheManager cacheManager;
  private final RepositorioJogos jogos;
  private final RepositorioDescontos descontos;

  ServicoAquecimentoCache(CacheManager cacheManager, RepositorioJogos jogos, RepositorioDescontos descontos) {
    this.cacheManager = cacheManager;
    this.jogos = jogos;
    this.descontos = descontos;
  }

  /**
   * Aquece no boot, em segundo plano.
   *
   * <p>O cache e em memoria, entao todo deploy comeca frio — e o primeiro aquecimento "normal" so
   * vinha depois da primeira rodada de precos, 4 min apos o boot. Nesse intervalo, cada SSR que
   * chegava pagava a consulta pesada (o ranking de descontos chegou a 13s, issue #16). Thread
   * propria e nao o executor de coleta, que tem fila de 1 e recusaria uma coleta manual pedida
   * nesse meio tempo. Falha aqui so e logada: o site funciona sem cache, so mais lento.
   */
  @EventListener(ApplicationReadyEvent.class)
  void aquecerNoBoot() {
    Thread.ofVirtual().name("aquecimento-cache-boot").start(() -> {
      long inicio = System.currentTimeMillis();
      try {
        popular();
        logger.info("Cache do catalogo aquecido no boot em {} ms", System.currentTimeMillis() - inicio);
      } catch (RuntimeException erro) {
        logger.warn("Falha ao aquecer o cache do catalogo no boot", erro);
      }
    });
  }

  void aquecer() {
    limpar(ConfiguracaoCache.CACHE_CATALOGO);
    limpar(ConfiguracaoCache.CACHE_DESCONTOS);
    popular();
  }

  private void popular() {
    // Catalogo (pagina 1, filtros default) e Mais Vendidos (mesmo endpoint, tamanho maior).
    jogos.listar(0, 20, "rank", "all", "all", null, null, null, null, java.util.List.of());
    jogos.listar(0, 40, "rank", "all", "all", null, null, null, null, java.util.List.of());
    // Home, secao "ofertas pra sua plataforma" sem preferencia salva (o caso de todo visitante).
    jogos.listar(0, 20, "discount", "all", "all", null, null, null, null, java.util.List.of());

    // Descontos: o topo e cacheado por ordenacao/tipo, e cada tamanho pedido e fatiado dele — entao
    // 3 chaves cobrem Home (rank 100, discount 200, dlc 50) e Gratuitos (discount 100).
    descontos.listarTopo("rank", "all");
    descontos.listarTopo("discount", "all");
    descontos.listarTopo("discount", "dlc");
  }

  private void limpar(String nomeCache) {
    Cache cache = cacheManager.getCache(nomeCache);
    if (cache != null) {
      cache.clear();
    }
  }
}
