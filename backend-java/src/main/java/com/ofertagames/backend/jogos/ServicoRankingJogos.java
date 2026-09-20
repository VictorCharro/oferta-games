package com.ofertagames.backend.jogos;

import com.ofertagames.backend.itad.ClienteItad;
import com.ofertagames.backend.itad.ItemOfertaItad;
import com.ofertagames.backend.itad.ItemPopularItad;
import com.ofertagames.backend.itad.RespostaOfertasItad;
import com.ofertagames.backend.steam.ServicoSteam;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Atualiza {@code games.rank}, a popularidade que ordena a Home, o catalogo e a fila de precos
 * (16/09/2026).
 *
 * <p>Ate aqui o rank era gravado uma unica vez, na importacao inicial de cada jogo, e nunca mais
 * mudava: a Home mostrava sempre os mesmos jogos, a primeira pagina do catalogo tinha titulos de
 * 2012 no topo, e jogo que entrou pela busca (Resonance, GTA VI) ficava com rank nulo, no fim de
 * 190 mil.
 *
 * <p>Tres fontes, todas "posicao 1 = mais popular", combinadas pelo <b>menor</b> valor (o jogo fica
 * com a melhor posicao que tiver em qualquer uma):
 *
 * <ol>
 *   <li><b>Steam</b> — mais vendidos, lancamentos populares e pre-venda popular. E o que esta
 *       bombando hoje; as 300 posicoes iniciais saem daqui.</li>
 *   <li><b>ITAD mais populares</b> — colecoes e listas de desejo, ate 1.000 jogos (a API nao pagina
 *       alem do offset 500). Popularidade de catalogo, mais estavel.</li>
 *   <li><b>ITAD ofertas por rank</b> — 5.000 jogos com oferta, ordenados pela popularidade da
 *       propria ITAD. E a fonte original do rank, agora refeita a cada rodada.</li>
 * </ol>
 *
 * <p>So atualiza jogo que ja esta no catalogo — quem importa jogo novo e {@link ServicoDescobertaJogos}.
 * Rank expira apos sete dias sem aparicao, somente quando todas as fontes responderam por completo.
 */
@Service
public class ServicoRankingJogos {
  private static final Logger logger = LoggerFactory.getLogger(ServicoRankingJogos.class);

  static final int PAGINAS_OFERTAS = 25;
  static final int TAMANHO_PAGINA_OFERTAS = 200;
  static final int TAMANHO_PAGINA_POPULARES = 500;

  private final ClienteItad itad;
  private final ServicoSteam steam;
  private final RepositorioJogos jogos;

  ServicoRankingJogos(ClienteItad itad, ServicoSteam steam, RepositorioJogos jogos) {
    this.itad = itad;
    this.steam = steam;
    this.jogos = jogos;
  }

  /** @return quantos jogos tiveram o rank alterado */
  public int atualizarRanking() {
    Map<String, Integer> ranks = new HashMap<>();
    FonteRanking steam = rankingDaSteam();
    FonteRanking populares = rankingDosMaisPopulares();
    FonteRanking ofertas = rankingDasOfertas();
    melhorPosicao(ranks, steam.ranks());
    melhorPosicao(ranks, populares.ranks());
    melhorPosicao(ranks, ofertas.ranks());
    if (ranks.isEmpty()) {
      logger.warn("Ranking: nenhuma fonte respondeu, rank mantido como estava");
      return 0;
    }
    int alterados = jogos.atualizarRanksPorItadId(ranks, steam.completa() && populares.completa() && ofertas.completa());
    logger.info("Ranking atualizado: {} jogos nas listas, {} com rank novo", ranks.size(), alterados);
    return alterados;
  }

  private static void melhorPosicao(Map<String, Integer> ranks, Map<String, Integer> novos) {
    novos.forEach((idItad, posicao) -> ranks.merge(idItad, posicao, Math::min));
  }

  /** Listas da Steam convertidas pra id da ITAD (mesma conversao da descoberta de jogos novos). */
  private FonteRanking rankingDaSteam() {
    Set<Integer> appIds = new LinkedHashSet<>();
    boolean completa = true;
    for (String lista : ServicoDescobertaJogos.LISTAS_STEAM) {
      try {
        var ids = steam.listarAppsDaBusca(lista, ServicoDescobertaJogos.ITENS_POR_LISTA);
        if (ids == null || ids.isEmpty()) completa = false;
        else appIds.addAll(ids);
      } catch (RuntimeException falha) { completa = false; }
    }
    if (appIds.isEmpty()) return new FonteRanking(Map.of(), false);
    List<Integer> ordem = new ArrayList<>(appIds);
    Map<String, Integer> ranks = new HashMap<>();
    try {
      Map<Integer, String> idsItad = itad.buscarIdsPorAppSteam(ordem);
      for (int i = 0; i < ordem.size(); i++) {
        String idItad = idsItad.get(ordem.get(i));
        if (idItad != null) ranks.merge(idItad, i + 1, Math::min);
      }
    } catch (RuntimeException falha) {
      completa = false;
      logger.warn("Ranking: listas da Steam ficaram de fora ({})", falha.toString());
    }
    return new FonteRanking(ranks, completa && !ranks.isEmpty());
  }

  private FonteRanking rankingDosMaisPopulares() {
    Map<String, Integer> ranks = new HashMap<>();
    boolean completa = true;
    for (int deslocamento = 0; deslocamento <= TAMANHO_PAGINA_POPULARES; deslocamento += TAMANHO_PAGINA_POPULARES) {
      try {
        List<ItemPopularItad> pagina = Objects.requireNonNullElse(
            itad.buscarMaisPopulares(deslocamento, TAMANHO_PAGINA_POPULARES), List.of());
        if (pagina.isEmpty()) completa = false;
        for (ItemPopularItad item : pagina) {
          if (item.id() != null && item.position() > 0) ranks.merge(item.id(), item.position(), Math::min);
        }
      } catch (RuntimeException falha) {
        completa = false;
        logger.warn("Ranking: pagina {} dos mais populares ficou de fora ({})", deslocamento, falha.toString());
      }
    }
    return new FonteRanking(ranks, completa && !ranks.isEmpty());
  }

  private FonteRanking rankingDasOfertas() {
    Map<String, Integer> ranks = new HashMap<>();
    boolean completa = true;
    for (int pagina = 0; pagina < PAGINAS_OFERTAS; pagina++) {
      int deslocamento = pagina * TAMANHO_PAGINA_OFERTAS;
      try {
        RespostaOfertasItad resposta = itad.buscarOfertas(TAMANHO_PAGINA_OFERTAS, deslocamento);
        List<ItemOfertaItad> itens = resposta == null ? List.of() : Objects.requireNonNullElse(resposta.list(), List.of());
        if (resposta == null || resposta.list() == null || (pagina == 0 && itens.isEmpty())) completa = false;
        for (int i = 0; i < itens.size(); i++) {
          ItemOfertaItad item = itens.get(i);
          if (item != null && item.id() != null) ranks.merge(item.id(), deslocamento + i + 1, Math::min);
        }
        if (!Boolean.TRUE.equals(resposta == null ? null : resposta.hasMore())) break;
      } catch (RuntimeException falha) {
        completa = false;
        logger.warn("Ranking: pagina {} das ofertas ficou de fora ({})", pagina, falha.toString());
        break;
      }
    }
    return new FonteRanking(ranks, completa && !ranks.isEmpty());
  }

  private record FonteRanking(Map<String, Integer> ranks, boolean completa) {}
}
