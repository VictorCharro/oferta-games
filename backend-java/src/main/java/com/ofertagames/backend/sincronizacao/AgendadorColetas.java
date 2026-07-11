package com.ofertagames.backend.sincronizacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.sync.scheduler.enabled", havingValue = "true")
class AgendadorColetas {
  private static final Logger logger = LoggerFactory.getLogger(AgendadorColetas.class);
  private static final String BLOQUEIO_COLETA = "coleta-oferta-games";

  private final ServicoSincronizacao sincronizacao;
  private final RepositorioControleColeta controle;

  AgendadorColetas(ServicoSincronizacao sincronizacao, RepositorioControleColeta controle) {
    this.sincronizacao = sincronizacao;
    this.controle = controle;
  }

  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.price-delay-ms:600000}",
      initialDelayString = "${app.sync.scheduler.price-initial-delay-ms:60000}")
  void coletarPrecos() {
    executarComBloqueio("precos", sincronizacao::sincronizarRodadaPrecos);
  }

  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.steam-delay-ms:900000}",
      initialDelayString = "${app.sync.scheduler.steam-initial-delay-ms:300000}")
  void coletarMetadadosSteam() {
    executarComBloqueio("steam", sincronizacao::sincronizarRodadaSteam);
  }

  private void executarComBloqueio(String tipo, Runnable coleta) {
    if (!controle.tentarAdquirir(BLOQUEIO_COLETA)) {
      logger.info("Coleta de {} ignorada: outra coleta ainda esta em andamento", tipo);
      return;
    }
    try {
      coleta.run();
    } catch (RuntimeException erro) {
      logger.error("Falha na coleta agendada de {}", tipo, erro);
    } finally {
      controle.liberar(BLOQUEIO_COLETA);
    }
  }
}
