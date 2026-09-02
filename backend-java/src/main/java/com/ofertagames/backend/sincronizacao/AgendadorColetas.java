package com.ofertagames.backend.sincronizacao;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara os jobs de coleta em intervalo fixo. Aqui fica so o <b>ritmo</b>; o que cada rodada faz
 * e quantos itens processa esta em {@link ServicoSincronizacao}.
 *
 * <p>Todo o agendamento e desligado quando {@code app.sync.scheduler.enabled} nao e {@code true} —
 * e o que mantem ambiente local sem coletar nada por acidente. Em producao fica ligado.
 *
 * <p>Usa {@code fixedDelay} (nao {@code fixedRate}), entao o intervalo conta a partir do
 * <b>fim</b> da execucao anterior: uma rodada lenta empurra a proxima, sem acumular disparos.
 * Somado a trava unica de {@link ServicoExecucaoColeta}, um job atrasado faz os outros do mesmo
 * periodo serem pulados em vez de enfileirados.
 *
 * <p>Todos os intervalos sao sobrescreviveis por variavel de ambiente, o que permite acelerar um
 * job pra drenar backlog sem alterar codigo.
 */
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

  // A varredura agendada de conquistas foi DESLIGADA em 01/09/2026. Nao readicionar sem reavaliar
  // o espaco em disco: ela percorria os 39 mil jogos com steam_app_id e, com 35% da fila
  // processada, game_achievements ja ocupava 121 MB; completar levaria o banco a ~590 MB, acima da
  // cota de 0,5 GB do plano free do Supabase.
  //
  // A coleta agora e sob demanda, disparada ao abrir a pagina do jogo
  // (ServicoConquistasSobDemanda), entao so entra no banco conquista de jogo que alguem olhou.
  //
  // sincronizarRodadaConquistasCatalogo continua existindo e o botao "conquistas-catalogo" do
  // painel de admin segue disparando a varredura manualmente, para quando fizer sentido.

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
