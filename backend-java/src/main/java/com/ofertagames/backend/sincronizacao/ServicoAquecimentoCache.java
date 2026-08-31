package com.ofertagames.backend.sincronizacao;

import com.ofertagames.backend.comum.ConfiguracaoCache;
import com.ofertagames.backend.descontos.RepositorioDescontos;
import com.ofertagames.backend.jogos.RepositorioJogos;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * Limpa e repopula o cache de catalogo/descontos logo apos cada rodada de precos.
 *
 * <p>Sem isso, o cache so seria populado pela primeira visita apos a expiracao, e quem chegasse
 * logo depois de uma sincronizacao pagaria a query pesada (~2s contra ~46ms quente). Limpar antes
 * de reaquecer tambem evita servir preco velho de uma entrada que ainda nao expirou.
 *
 * <p>So aquece as combinacoes que Home, Catalogo, Mais Vendidos e Gratuitos usam de fato — e a
 * lista precisa ser mantida em sincronia com essas telas. Uma combinacao nova no frontend que nao
 * seja adicionada aqui simplesmente nunca sera aquecida, e quem abrir aquela tela paga o custo
 * frio.
 */
@Service
class ServicoAquecimentoCache {
  private final CacheManager cacheManager;
  private final RepositorioJogos jogos;
  private final RepositorioDescontos descontos;

  ServicoAquecimentoCache(CacheManager cacheManager, RepositorioJogos jogos, RepositorioDescontos descontos) {
    this.cacheManager = cacheManager;
    this.jogos = jogos;
    this.descontos = descontos;
  }

  void aquecer() {
    limpar(ConfiguracaoCache.CACHE_CATALOGO);
    limpar(ConfiguracaoCache.CACHE_DESCONTOS);

    // Catalogo (pagina 1, filtros default) e Mais Vendidos (mesmo endpoint, tamanho maior).
    jogos.listar(0, 20, "rank", "all", "all", null, null, null, null, java.util.List.of());
    jogos.listar(0, 40, "rank", "all", "all", null, null, null, null, java.util.List.of());

    // Home: os usados em Home.ngOnInit.
    descontos.listarMelhores(100, "rank", "all");
    descontos.listarMelhores(200, "discount", "all");
    descontos.listarMelhores(50, "discount", "dlc");

    // Gratuitos: mesmo endpoint, combinacao propria (tamanho 100, ordenacao discount).
    descontos.listarMelhores(100, "discount", "all");
  }

  private void limpar(String nomeCache) {
    Cache cache = cacheManager.getCache(nomeCache);
    if (cache != null) {
      cache.clear();
    }
  }
}
