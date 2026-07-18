package com.ofertagames.backend.jogos;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConfiguracaoCacheCatalogo {

  public static final String CACHE_CATALOGO = "catalogoJogos";

  // TTL curto pra acompanhar a sincronizacao de precos sem martelar o banco a cada abertura do catalogo.
  @Bean
  CacheManager cacheManager() {
    CaffeineCacheManager gerenciador = new CaffeineCacheManager(CACHE_CATALOGO);
    gerenciador.setCaffeine(Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(500));
    return gerenciador;
  }
}
