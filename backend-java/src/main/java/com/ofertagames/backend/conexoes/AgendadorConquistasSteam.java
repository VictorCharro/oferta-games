package com.ofertagames.backend.conexoes;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.sync.scheduler.enabled", havingValue = "true")
class AgendadorConquistasSteam {
  private final ServicoConexoesSteam steam;

  AgendadorConquistasSteam(ServicoConexoesSteam steam) {
    this.steam = steam;
  }

  @Scheduled(fixedDelayString = "${app.sync.scheduler.steam-achievements-delay-ms:1800000}",
      initialDelayString = "${app.sync.scheduler.steam-achievements-initial-delay-ms:600000}")
  void sincronizarConquistas() {
    steam.sincronizarConquistas(20);
  }
}
