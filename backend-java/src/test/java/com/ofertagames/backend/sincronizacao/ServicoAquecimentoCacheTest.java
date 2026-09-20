package com.ofertagames.backend.sincronizacao;

import static org.mockito.Mockito.*;
import com.ofertagames.backend.comum.ConfiguracaoCache;
import com.ofertagames.backend.descontos.RepositorioDescontos;
import com.ofertagames.backend.jogos.RepositorioJogos;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

class ServicoAquecimentoCacheTest {
  @Test void reaqueceLancamentosDepoisDeInvalidarDescontos() {
    var manager = mock(CacheManager.class);
    var cache = mock(Cache.class);
    var descontos = mock(RepositorioDescontos.class);
    when(manager.getCache(ConfiguracaoCache.CACHE_DESCONTOS)).thenReturn(cache);
    new ServicoAquecimentoCache(manager, mock(RepositorioJogos.class), descontos).aquecer();
    var ordem = inOrder(cache, descontos);
    ordem.verify(cache).clear();
    ordem.verify(descontos).listarLancamentos();
  }
}
