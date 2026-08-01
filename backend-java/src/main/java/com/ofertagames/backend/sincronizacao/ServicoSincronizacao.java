package com.ofertagames.backend.sincronizacao;

import com.ofertagames.backend.instantgaming.ServicoInstantGaming;
import com.ofertagames.backend.itad.ClienteItad;
import com.ofertagames.backend.itad.ItemOfertaItad;
import com.ofertagames.backend.itad.RespostaOfertasItad;
import com.ofertagames.backend.jogos.RepositorioJogos;
import com.ofertagames.backend.jogos.ServicoCatalogo;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ServicoSincronizacao {
  private static final int TAMANHO_PAGINA = 50;
  private static final int LIMITE_RELEVANTES = 200;
  private static final int LIMITE_GERAIS = 4_800;
  private static final int TAMANHO_LOTE_PRECOS = 200;
  private static final int LIMITE_METADADOS_STEAM = 60;
  private static final int LIMITE_DETALHES_JOGOS = 60;
  // Valor alto temporario: o fix do regex de agecheck (750920/Shadow of the Tomb Raider etc)
  // liberou o steam_app_id de centenas de jogos que antes ficavam bloqueados pra sempre,
  // dobrando o backlog de conquistas pendentes (~769 -> ~1724). Volta pro ritmo baixo de
  // manutencao quando o backlog zerar.
  private static final int LIMITE_CONQUISTAS_CATALOGO = 250;
  // Ritmo de manutencao: a varredura ja cobriu o catalogo da Instant Gaming quase por completo
  // (crescimento estagnado), entao agora e so acompanhar produtos novos entrando no catalogo
  // deles.
  private static final int LIMITE_INSTANT_GAMING_ESCANEAMENTO = 30;
  private static final int LIMITE_INSTANT_GAMING_CASAMENTO = 200;
  // Comecou em 30/1h, mas com o casamento ja em ~100 jogos poucas horas apos a varredura
  // acelerada, esse ritmo nao dava conta de manter os precos atualizados com frequencia
  // razoavel. Ajustado pra 100 a cada 30min (ver delay em AgendadorColetas); reavaliar se o
  // total de jogos casados continuar crescendo bem alem disso.
  private static final int LIMITE_INSTANT_GAMING_PRECOS = 100;
  private static final Logger logger = LoggerFactory.getLogger(ServicoSincronizacao.class);

  private final ClienteItad itad;
  private final ServicoCatalogo catalogo;
  private final RepositorioJogos jogos;
  private final ServicoAquecimentoCache aquecimentoCache;
  private final ServicoInstantGaming instantGaming;

  ServicoSincronizacao(ClienteItad itad, ServicoCatalogo catalogo, RepositorioJogos jogos, ServicoAquecimentoCache aquecimentoCache, ServicoInstantGaming instantGaming) {
    this.itad = itad;
    this.catalogo = catalogo;
    this.jogos = jogos;
    this.aquecimentoCache = aquecimentoCache;
    this.instantGaming = instantGaming;
  }

  /** Mantido para diagnostico e sincronizacao manual pontual; nao e usado pelo agendador. */
  public ResultadoSincronizacao sincronizarPagina(int pagina) {
    int paginaSegura = Math.max(0, pagina);
    int deslocamento = paginaSegura * TAMANHO_PAGINA;
    RespostaOfertasItad resposta = itad.buscarOfertas(TAMANHO_PAGINA, deslocamento);
    List<ItemOfertaItad> itens = resposta == null ? List.of() : Objects.requireNonNullElse(resposta.list(), List.of());

    int sincronizadas = catalogo.salvarOfertasDoSync(itens, deslocamento);
    int ignoradas = Math.max(0, itens.size() - sincronizadas);
    boolean temMais = Boolean.TRUE.equals(resposta == null ? null : resposta.hasMore());

    return new ResultadoSincronizacao(true, sincronizadas, ignoradas, temMais, temMais ? paginaSegura + 1 : null, 0);
  }

  public ResultadoRodadaColeta sincronizarRodadaPrecos() {
    List<RepositorioJogos.JogoParaSincronizar> selecionados = jogos.listarParaSincronizar(
        LIMITE_RELEVANTES, LIMITE_GERAIS);
    if (selecionados.isEmpty()) {
      logger.info("Coleta agendada de precos sem jogos elegiveis");
      return new ResultadoRodadaColeta(0, 0);
    }

    int jogosAtualizados = 0;
    int ofertasAtualizadas = 0;
    for (int inicio = 0; inicio < selecionados.size(); inicio += TAMANHO_LOTE_PRECOS) {
      int fim = Math.min(inicio + TAMANHO_LOTE_PRECOS, selecionados.size());
      try {
        var resultado = atualizarLoteComTentativas(selecionados.subList(inicio, fim));
        jogosAtualizados += resultado.jogosAtualizados();
        ofertasAtualizadas += resultado.ofertasAtualizadas();
      } catch (RuntimeException erro) {
        logger.error("Falha definitiva ao atualizar o lote de precos {}-{}", inicio, fim - 1, erro);
      }
    }

    if (ofertasAtualizadas > 0) {
      try {
        aquecimentoCache.aquecer();
      } catch (RuntimeException erro) {
        logger.warn("Falha ao reaquecer o cache do catalogo apos a coleta de precos", erro);
      }
    }

    logger.info(
        "Coleta agendada de precos concluida: {} jogos e {} ofertas atualizados",
        jogosAtualizados,
        ofertasAtualizadas);
    return new ResultadoRodadaColeta(jogosAtualizados, ofertasAtualizadas);
  }

  public ResultadoRodadaColeta sincronizarRodadaSteam() {
    int atualizados = catalogo.preencherMetadadosSteam(LIMITE_METADADOS_STEAM);
    logger.info("Coleta agendada de metadados Steam concluida: {} jogos atualizados", atualizados);
    return new ResultadoRodadaColeta(atualizados, 0);
  }

  public ResultadoRodadaColeta sincronizarRodadaDetalhes() {
    int atualizados = catalogo.preencherDetalhesJogos(LIMITE_DETALHES_JOGOS);
    logger.info("Coleta agendada de detalhes (sobre/reviews) concluida: {} jogos atualizados", atualizados);
    return new ResultadoRodadaColeta(atualizados, 0);
  }

  public ResultadoRodadaColeta sincronizarRodadaConquistasCatalogo() {
    int atualizados = catalogo.preencherConquistas(LIMITE_CONQUISTAS_CATALOGO);
    logger.info("Coleta agendada de conquistas do catalogo concluida: {} jogos atualizados", atualizados);
    return new ResultadoRodadaColeta(atualizados, 0);
  }

  public ResultadoRodadaColeta sincronizarRodadaInstantGamingEscaneamento() {
    int encontrados = instantGaming.escanearCatalogo(LIMITE_INSTANT_GAMING_ESCANEAMENTO);
    logger.info("Varredura agendada do catalogo Instant Gaming concluida: {} produtos novos", encontrados);
    return new ResultadoRodadaColeta(encontrados, 0);
  }

  public ResultadoRodadaColeta sincronizarRodadaInstantGamingCasamento() {
    int casados = instantGaming.casarComCatalogo(LIMITE_INSTANT_GAMING_CASAMENTO);
    logger.info("Casamento agendado com o catalogo Instant Gaming concluido: {} jogos casados", casados);
    return new ResultadoRodadaColeta(casados, 0);
  }

  public ResultadoRodadaColeta sincronizarRodadaInstantGamingPrecos() {
    int atualizados = instantGaming.atualizarPrecos(LIMITE_INSTANT_GAMING_PRECOS);
    logger.info("Coleta agendada de precos Instant Gaming concluida: {} jogos atualizados", atualizados);
    return new ResultadoRodadaColeta(0, atualizados);
  }

  private ServicoCatalogo.ResultadoAtualizacaoLote atualizarLoteComTentativas(
      List<RepositorioJogos.JogoParaSincronizar> lote) {
    RuntimeException ultimoErro = null;
    for (int tentativa = 1; tentativa <= 3; tentativa++) {
      try {
        return catalogo.atualizarPrecosEmLote(lote);
      } catch (RuntimeException erro) {
        ultimoErro = erro;
        if (tentativa == 3) {
          break;
        }
        logger.warn("Falha no lote de precos; nova tentativa em 10 segundos ({}/3)", tentativa, erro);
        aguardarProximaTentativa();
      }
    }
    throw new IllegalStateException("Falha ao atualizar lote de precos", ultimoErro);
  }

  private void aguardarProximaTentativa() {
    try {
      Thread.sleep(10_000);
    } catch (InterruptedException erro) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Coleta de precos interrompida", erro);
    }
  }
}
