package com.ofertagames.backend.conexoes;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.sync.scheduler.enabled", havingValue = "true")
class AgendadorCapasBibliotecaSteam {
  private final ServicoConexoesSteam steam;

  AgendadorCapasBibliotecaSteam(ServicoConexoesSteam steam) {
    this.steam = steam;
  }

  @Scheduled(fixedDelayString = "${app.sync.scheduler.steam-library-covers-delay-ms:900000}",
      initialDelayString = "${app.sync.scheduler.steam-library-covers-initial-delay-ms:660000}")
  void preencherCapas() {
    steam.preencherCapasBiblioteca(60);
  }
}
