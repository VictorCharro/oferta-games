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

  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.detalhes-delay-ms:900000}",
      initialDelayString = "${app.sync.scheduler.detalhes-initial-delay-ms:420000}")
  void coletarDetalhesJogos() {
    execucao.executar("detalhes", sincronizacao::sincronizarRodadaDetalhes);
  }

  // Delay baixo temporario (2 min) pra zerar o backlog de conquistas do catalogo rapido;
  // volta pra 10800000 (3h) assim que o backlog estiver zerado, ja que dai e so acompanhar
  // jogos novos entrando no catalogo.
  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.conquistas-catalogo-delay-ms:120000}",
      initialDelayString = "${app.sync.scheduler.conquistas-catalogo-initial-delay-ms:540000}")
  void coletarConquistasCatalogo() {
    execucao.executar("conquistas-catalogo", sincronizacao::sincronizarRodadaConquistasCatalogo);
  }
}
