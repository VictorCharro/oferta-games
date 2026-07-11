package com.ofertagames.backend.sincronizacao;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.sync.scheduler.enabled", havingValue = "true")
class AgendadorColetas {
  private final ServicoSincronizacao sincronizacao;
  private final ServicoExecucaoColeta execucao;

  AgendadorColetas(ServicoSincronizacao sincronizacao, ServicoExecucaoColeta execucao) {
    this.sincronizacao = sincronizacao;
    this.execucao = execucao;
  }

  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.price-delay-ms:600000}",
      initialDelayString = "${app.sync.scheduler.price-initial-delay-ms:60000}")
  void coletarPrecos() {
    execucao.executar("precos", sincronizacao::sincronizarRodadaPrecos);
  }

  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.steam-delay-ms:900000}",
      initialDelayString = "${app.sync.scheduler.steam-initial-delay-ms:300000}")
  void coletarMetadadosSteam() {
    execucao.executar("steam", sincronizacao::sincronizarRodadaSteam);
  }
}
