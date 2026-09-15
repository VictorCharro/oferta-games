package com.ofertagames.backend.jogos;

import com.ofertagames.backend.comum.GeradorSlug;
import com.ofertagames.backend.itad.ClienteItad;
import com.ofertagames.backend.itad.InfoJogoItad;
import com.ofertagames.backend.steam.ServicoSteam;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Descoberta de jogos novos e populares (15/09/2026).
 *
 * <p>Antes o catalogo so ganhava jogo novo quando alguem buscava por ele no site: a coleta de
 * precos so atualiza o que ja existe. Lancamento famoso (ex.: Resonance: A Plague Tale Legacy) ficava
 * de fora ate um visitante procurar pelo nome.
 *
 * <p>Fonte: as listas da busca da loja Steam — mais vendidos, lancamentos populares e pre-venda
 * popular. A ITAD nao tem lista de lancamentos, mas converte app id da Steam no id dela, que e a
 * chave do catalogo. Jogo que so existe fora da Steam continua entrando pela busca.
 *
 * <p>Cada jogo novo sai pronto na mesma rodada: preco (ITAD), capa e se e DLC, detalhes e
 * conquistas (mesmo fluxo do "Preencher tudo agora" do admin), sem esperar as filas.
 */
@Service
public class ServicoDescobertaJogos {
  private static final Logger logger = LoggerFactory.getLogger(ServicoDescobertaJogos.class);

  static final List<String> LISTAS_STEAM = List.of("topsellers", "popularnew", "popularcomingsoon");
  static final int ITENS_POR_LISTA = 100;
  /** Teto de jogos novos por rodada: cada um custa ~5 chamadas externas no preenchimento. */
  static final int MAX_NOVOS_POR_RODADA = 40;

  private final ServicoSteam steam;
  private final ClienteItad itad;
  private final RepositorioJogos jogos;
  private final ServicoCatalogo catalogo;

  ServicoDescobertaJogos(ServicoSteam steam, ClienteItad itad, RepositorioJogos jogos, ServicoCatalogo catalogo) {
    this.steam = steam;
    this.itad = itad;
    this.jogos = jogos;
    this.catalogo = catalogo;
  }

  /** @return quantos jogos novos entraram no catalogo */
  public int descobrir() {
    // Ordem das listas importa: a posicao vira o rank dos jogos novos (mais vendido primeiro).
    Set<Integer> appIds = new LinkedHashSet<>();
    for (String lista : LISTAS_STEAM) appIds.addAll(steam.listarAppsDaBusca(lista, ITENS_POR_LISTA));
    if (appIds.isEmpty()) {
      logger.warn("Descoberta de jogos: a Steam nao devolveu nenhuma lista");
      return 0;
    }

    List<Integer> ordem = new ArrayList<>(appIds);
    Set<Integer> conhecidos = jogos.steamAppIdsExistentes(ordem);
    List<Integer> candidatos = ordem.stream().filter(id -> !conhecidos.contains(id)).toList();
    if (candidatos.isEmpty()) return 0;

    Map<Integer, String> idsItad = itad.buscarIdsPorAppSteam(candidatos);
    // Jogo que ja esta no catalogo (entrou pela busca ou por outra loja) mas ainda sem steam_app_id:
    // so ganha o app id, o que destrava capa, detalhes e conquistas nas filas normais.
    Map<String, Long> existentesPorItad = jogos.idsPorItadIds(new ArrayList<>(idsItad.values()));

    List<RepositorioJogos.JogoParaSincronizar> novos = new ArrayList<>();
    List<String> slugsNovos = new ArrayList<>();
    for (Integer appId : candidatos) {
      String idItad = idsItad.get(appId);
      if (idItad == null) continue;
      Long existente = existentesPorItad.get(idItad);
      if (existente != null) {
        jogos.atualizarMetadadosSteam(existente, null, null, appId);
        continue;
      }
      if (novos.size() >= MAX_NOVOS_POR_RODADA) continue;
      try {
        InfoJogoItad info = itad.buscarInfoJogo(idItad);
        if (info == null || info.title() == null || (info.type() != null && !"game".equals(info.type()))) continue;
        String slug = info.slug() == null || info.slug().isBlank() ? GeradorSlug.porTitulo(info.title()) : info.slug();
        String capa = info.assets() == null ? null : info.assets().banner400();
        int rank = ordem.indexOf(appId) + 1;
        var salvos = jogos.salvarJogosItad(List.of(new RepositorioJogos.JogoParaSalvar(idItad, info.title(), slug, capa, rank)));
        if (salvos.isEmpty()) continue; // conteudo que nao e jogo ou bloqueado
        long jogoId = salvos.get(0).id();
        jogos.atualizarMetadadosSteam(jogoId, null, null, appId);
        novos.add(new RepositorioJogos.JogoParaSincronizar(jogoId, idItad));
        slugsNovos.add(slug);
      } catch (RuntimeException falha) {
        logger.warn("Descoberta: falha ao importar app {} ({})", appId, falha.toString());
      }
    }

    if (!novos.isEmpty()) {
      try {
        catalogo.atualizarPrecosEmLote(novos);
      } catch (RuntimeException falha) {
        logger.warn("Descoberta: precos dos jogos novos ficam pra coleta normal ({})", falha.toString());
      }
      for (String slug : slugsNovos) {
        try {
          catalogo.preencherTudoDoJogo(slug);
        } catch (RuntimeException falha) {
          logger.warn("Descoberta: preenchimento de {} fica pras filas normais ({})", slug, falha.toString());
        }
      }
    }
    logger.info("Descoberta de jogos: {} candidatos fora do catalogo, {} novos importados: {}", candidatos.size(), novos.size(), slugsNovos);
    return novos.size();
  }
}
