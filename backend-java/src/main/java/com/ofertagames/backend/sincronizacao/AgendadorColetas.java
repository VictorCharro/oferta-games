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

  // Acelerado temporariamente em 09/08/2026 (ver LIMITE_CONQUISTAS_CATALOGO em
  // ServicoSincronizacao): reverter pra 10800000 (3h) quando o backlog de conquistas zerar ou
  // estagnar.
  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.conquistas-catalogo-delay-ms:120000}",
      initialDelayString = "${app.sync.scheduler.conquistas-catalogo-initial-delay-ms:540000}")
  void coletarConquistasCatalogo() {
    execucao.executar("conquistas-catalogo", sincronizacao::sincronizarRodadaConquistasCatalogo);
  }

  // Instant Gaming nao tem API: varredura por id numerico de produto (permitida pelo robots.txt
  // deles, diferente da busca do site). Ritmo de manutencao (15min): a descoberta ja cobriu o
  // catalogo quase todo, entao agora e so acompanhar produtos novos entrando no catalogo deles.
  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.instant-gaming-scan-delay-ms:900000}",
      initialDelayString = "${app.sync.scheduler.instant-gaming-scan-initial-delay-ms:600000}")
  void escanearInstantGaming() {
    execucao.executar("instant-gaming-escaneamento", sincronizacao::sincronizarRodadaInstantGamingEscaneamento);
  }

  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.instant-gaming-match-delay-ms:1200000}",
      initialDelayString = "${app.sync.scheduler.instant-gaming-match-initial-delay-ms:660000}")
  void casarInstantGaming() {
    execucao.executar("instant-gaming-casamento", sincronizacao::sincronizarRodadaInstantGamingCasamento);
  }

  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.instant-gaming-price-delay-ms:1800000}",
      initialDelayString = "${app.sync.scheduler.instant-gaming-price-initial-delay-ms:720000}")
  void atualizarPrecosInstantGaming() {
    execucao.executar("instant-gaming-precos", sincronizacao::sincronizarRodadaInstantGamingPrecos);
  }

  // Uma vez por dia basta: so apaga linhas com mais de 90 dias (RETENCAO_HISTORICO_PRECOS_DIAS em
  // ServicoSincronizacao), nao precisa de ritmo fino como os jobs de coleta.
  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.historico-precos-poda-delay-ms:86400000}",
      initialDelayString = "${app.sync.scheduler.historico-precos-poda-initial-delay-ms:900000}")
  void podarHistoricoDePrecos() {
    execucao.executar("historico-precos-poda", sincronizacao::podarHistoricoDePrecos);
  }
}
