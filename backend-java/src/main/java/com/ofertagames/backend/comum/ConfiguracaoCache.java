package com.ofertagames.backend.comum;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache em memoria (Caffeine) das leituras caras de catalogo e descontos.
 *
 * <p>E cache por instancia, nao distribuido: cada processo tem o seu, e um deploy zera tudo. Nao
 * ha invalidacao manual — as entradas so expiram por tempo, entao um preco recem-sincronizado
 * pode levar ate um TTL pra aparecer.
 *
 * <p>{@code ServicoAquecimentoCache} reaquece as combinacoes usadas pela Home logo apos cada
 * rodada de precos, pra ninguem pagar o caminho frio (medido em ~2s contra ~46ms quente).
 */
@Configuration
public class ConfiguracaoCache {

  public static final String CACHE_CATALOGO = "catalogoJogos";
  public static final String CACHE_DESCONTOS = "descontosTop";
  public static final String CACHE_AVALIACOES_STEAM = "avaliacoesSteam";

  /**
   * TTL propositalmente <b>maior</b> que o intervalo do job de precos
   * ({@code APP_SYNC_SCHEDULER_PRICE_DELAY_MS}, 10 min).
   *
   * <p>Os dois eram 10 min, e isso criava uma corrida: como {@code ServicoAquecimentoCache} so
   * reaquece <i>depois</i> de cada rodada, a entrada as vezes expirava antes do reaquecimento
   * chegar e o usuario pagava o caminho frio — medido em ~2s contra ~46ms quente. Com folga de
   * 5 min o reaquecimento sempre chega primeiro.
   *
   * <p>Isso nao deixa o preco mais velho na pratica: o aquecimento reescreve as entradas a cada
   * rodada, entao o TTL so vale pras combinacoes que ninguem aquece (paginas fundas do catalogo,
   * filtros incomuns).
   */
  @Bean
  CacheManager cacheManager() {
    CaffeineCacheManager gerenciador = new CaffeineCacheManager(
        CACHE_CATALOGO, CACHE_DESCONTOS, CACHE_AVALIACOES_STEAM);
    gerenciador.setCaffeine(Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).maximumSize(500));
    return gerenciador;
  }
}
