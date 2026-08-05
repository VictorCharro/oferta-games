package com.ofertagames.backend.sincronizacao;

import com.ofertagames.backend.comum.ConfiguracaoCache;
import com.ofertagames.backend.descontos.RepositorioDescontos;
import com.ofertagames.backend.jogos.RepositorioJogos;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

// Sem isso, o cache so e populado pela primeira visita apos expirar (ou apos um preco mudar),
// entao quem chega logo depois de uma sincronizacao ainda paga a query pesada. Aqui o cache e
// limpo e reaquecido com as combinacoes mais usadas (home, catalogo, mais vendidos) logo depois
// de cada rodada de precos, pra ninguem pegar o cache frio nem os preços velhos.
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
    jogos.listar(0, 20, "rank", "all", "all", null, null, null, null);
    jogos.listar(0, 40, "rank", "all", "all", null, null, null, null);

    // Home: os dois usados em Home.ngOnInit.
    descontos.listarMelhores(100, "rank");
    descontos.listarMelhores(200, "discount");

    // Gratuitos: mesmo endpoint, combinacao propria (tamanho 100, ordenacao discount).
    descontos.listarMelhores(100, "discount");
  }

  private void limpar(String nomeCache) {
    Cache cache = cacheManager.getCache(nomeCache);
    if (cache != null) {
      cache.clear();
    }
  }
}
