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

  // Varredura de conquistas do catalogo RELIGADA em 15/09/2026. Tinha sido desligada em 01/09
  // porque game_achievements morava no Supabase free (cota de 0,5 GB); desde a migracao do
  // catalogo pro Postgres da VM (disco de 48 GB) isso nao se aplica: 13,6 mil jogos com conquistas
  // ocupam 118 MB e ~65% dos jogos verificados nao tem conquista nenhuma (so ganham o carimbo
  // achievements_checked_at).
  //
  // Ritmo: 250 jogos (LIMITE_CONQUISTAS_CATALOGO) a cada 10 min drena os ~19 mil pendentes em
  // ~13h. Cada jogo custa 1 chamada a Steam (esquema) ou 2 (com percentuais), bem abaixo das 100 mil
  // por dia da chave. Com a fila vazia, cada rodada so pega jogo novo que entrou no catalogo.
  // A coleta sob demanda ao abrir a pagina (ServicoConquistasSobDemanda) continua valendo pro jogo
  // que alguem abre antes de a varredura chegar nele.
  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.conquistas-catalogo-varredura-delay-ms:600000}",
      initialDelayString = "${app.sync.scheduler.conquistas-catalogo-varredura-initial-delay-ms:480000}")
  void coletarConquistasCatalogo() {
    execucao.executar("conquistas-catalogo", sincronizacao::sincronizarRodadaConquistasCatalogo);
  }

  // Descoberta de jogos novos e populares pelas listas da Steam (ServicoDescobertaJogos). A cada 3h:
  // as listas mudam devagar (mais vendidos e lancamentos da semana), e cada rodada custa 3 chamadas a
  // loja + 1 a ITAD quando nao ha nada novo.
  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.descoberta-delay-ms:10800000}",
      initialDelayString = "${app.sync.scheduler.descoberta-initial-delay-ms:240000}")
  void descobrirJogosNovos() {
    execucao.executar("descoberta", sincronizacao::sincronizarRodadaDescoberta);
  }

  // Ranking de popularidade (ServicoRankingJogos), 1x por dia: as listas de origem mudam por dia,
  // nao por hora, e a rodada custa ~30 chamadas externas. Sem ele o rank ficava congelado no valor
  // da importacao inicial de cada jogo (Home sempre com os mesmos jogos).
  @Scheduled(
      fixedDelayString = "${app.sync.scheduler.ranking-delay-ms:86400000}",
      initialDelayString = "${app.sync.scheduler.ranking-initial-delay-ms:120000}")
  void atualizarRanking() {
    execucao.executar("ranking", sincronizacao::sincronizarRodadaRanking);
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
