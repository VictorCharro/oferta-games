package com.ofertagames.backend.jogos;

import com.ofertagames.backend.comum.GeradorSlug;
import com.ofertagames.backend.itad.ClienteItad;
import com.ofertagames.backend.itad.ItemOfertaItad;
import com.ofertagames.backend.itad.OfertaPrecoItad;
import com.ofertagames.backend.itad.ResultadoBuscaItad;
import com.ofertagames.backend.itad.ResultadoPrecoItad;
import com.ofertagames.backend.instantgaming.ServicoInstantGaming;
import com.ofertagames.backend.notificacoes.RepositorioNotificacoes;
import com.ofertagames.backend.steam.DetalhesAplicativoSteam;
import com.ofertagames.backend.steam.ServicoSteam;
import com.ofertagames.backend.steam.ServicoSteam.ReviewsSteam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ServicoCatalogo {
  private final RepositorioJogos jogos;
  private final ClienteItad itad;
  private final ServicoSteam steam;
  private final RepositorioNotificacoes notificacoes;
  private final ServicoInstantGaming instantGaming;

  ServicoCatalogo(RepositorioJogos jogos, ClienteItad itad, ServicoSteam steam, RepositorioNotificacoes notificacoes, ServicoInstantGaming instantGaming) {
    this.jogos = jogos;
    this.itad = itad;
    this.steam = steam;
    this.notificacoes = notificacoes;
    this.instantGaming = instantGaming;
  }

  public List<ResumoJogo> buscarComFallbackItad(String busca) {
    String termo = busca == null ? "" : busca.trim();
    if (termo.length() < 2) {
      throw new BuscaCurtaException();
    }

    List<ResumoJogo> locais = jogos.listar(0, 20, "rank", "all", "all", null, null, null, termo);
    if (!locais.isEmpty()) {
      return locais;
    }

    List<ResultadoBuscaItad> encontrados = Objects.requireNonNullElse(itad.buscarJogos(termo), List.of());
    if (encontrados.isEmpty()) {
      return List.of();
    }

    List<RepositorioJogos.JogoParaSalvar> jogosParaSalvar = encontrados.stream()
        .map(jogo -> {
          String slug = jogo.slug() == null || jogo.slug().isBlank() ? GeradorSlug.porTitulo(jogo.title()) : jogo.slug();
          String capa = jogo.assets() == null ? null : jogo.assets().banner400();
          return new RepositorioJogos.JogoParaSalvar(jogo.id(), jogo.title(), slug, capa, null);
        })
        .toList();

    jogos.salvarJogosItad(jogosParaSalvar);

    return jogos.listarPorItadIds(encontrados.stream().map(ResultadoBuscaItad::id).toList());
  }

  public ResultadoAtualizacaoJogo atualizarPrecos(String slug) {
    JogoParaAtualizar jogo = jogos.buscarParaAtualizar(slug).orElseThrow(JogoNaoEncontradoException::new);
    boolean temItad = jogo.itadId() != null && !jogo.itadId().isBlank();
    boolean temInstantGaming = jogo.instantGamingUrl() != null;
    if (!temItad && !temInstantGaming) {
      throw new JogoSemItadException();
    }

    int atualizadas = 0;
    if (temItad) {
      List<ResultadoPrecoItad> resultados = Objects.requireNonNullElse(itad.buscarPrecos(jogo.itadId()), List.of());
      ResultadoPrecoItad resultado = resultados.isEmpty() ? null : resultados.get(0);
      if (resultado != null && resultado.deals() != null) {
        for (OfertaPrecoItad oferta : resultado.deals()) {
          if (oferta.shop() == null || oferta.price() == null || oferta.url() == null) {
            continue;
          }

          jogos.salvarOferta(new OfertaParaSalvar(
              jogo.id(),
              "itad",
              oferta.shop().name(),
              oferta.price().amount(),
              oferta.regular() == null ? null : oferta.regular().amount(),
              "BRL",
              oferta.url()));
          atualizadas++;
        }
        atualizarMetadadosSteamSeNecessario(jogo, resultado.deals());
      }
    }
    if (temInstantGaming && instantGaming.atualizarPrecoImediato(jogo.id())) {
      atualizadas++;
    }

    return new ResultadoAtualizacaoJogo(true, atualizadas);
  }

  public ResultadoAtualizacaoLote atualizarPrecosEmLote(List<RepositorioJogos.JogoParaSincronizar> jogosParaAtualizar) {
    if (jogosParaAtualizar == null || jogosParaAtualizar.isEmpty()) {
      return new ResultadoAtualizacaoLote(0, 0);
    }

    Map<String, Long> jogosPorIdItad = new LinkedHashMap<>();
    for (RepositorioJogos.JogoParaSincronizar jogo : jogosParaAtualizar) {
      if (jogo.itadId() != null && !jogo.itadId().isBlank()) {
        jogosPorIdItad.put(jogo.itadId(), jogo.id());
      }
    }
    if (jogosPorIdItad.isEmpty()) {
      return new ResultadoAtualizacaoLote(0, 0);
    }

    List<ResultadoPrecoItad> resultados = Objects.requireNonNullElse(
        itad.buscarPrecos(new ArrayList<>(jogosPorIdItad.keySet())), List.of());
    if (resultados.isEmpty()) {
      throw new IllegalStateException("A ITAD nao retornou precos para o lote da coleta agendada");
    }

    Map<Long, List<OfertaParaSalvar>> ofertasPorJogo = new LinkedHashMap<>();
    for (ResultadoPrecoItad resultado : resultados) {
      if (resultado == null || resultado.id() == null) {
        continue;
      }
      Long jogoId = jogosPorIdItad.get(resultado.id());
      if (jogoId == null) {
        continue;
      }

      List<OfertaParaSalvar> ofertasAtuais = new ArrayList<>();
      List<OfertaPrecoItad> ofertasItad = resultado.deals();
      if (ofertasItad != null) {
        for (OfertaPrecoItad oferta : ofertasItad) {
          if (oferta == null
              || oferta.shop() == null
              || oferta.shop().name() == null || oferta.shop().name().isBlank()
              || oferta.price() == null || oferta.price().amount() == null
              || oferta.url() == null || oferta.url().isBlank()) {
            continue;
          }
          ofertasAtuais.add(new OfertaParaSalvar(
              jogoId,
              "itad",
              oferta.shop().name(),
              oferta.price().amount(),
              oferta.regular() == null ? null : oferta.regular().amount(),
              "BRL",
              oferta.url()));
        }
      }
      ofertasPorJogo.put(jogoId, ofertasAtuais);
    }

    if (ofertasPorJogo.isEmpty()) {
      throw new IllegalStateException("A ITAD nao retornou jogos reconhecidos para o lote da coleta agendada");
    }

    List<Long> jogosIds = new ArrayList<>(ofertasPorJogo.keySet());
    Map<Long, java.math.BigDecimal> precosAnteriores = jogos.precosMinimos(jogosIds);
    int ofertasAtualizadas = jogos.substituirOfertasItadEmLote(ofertasPorJogo);
    Map<Long, java.math.BigDecimal> precosAtuais = jogos.precosMinimos(jogosIds);
    for (Long jogoId : jogosIds) {
      notificacoes.registrarQueda(jogoId, precosAnteriores.get(jogoId), precosAtuais.get(jogoId));
    }
    jogos.marcarPrecosSincronizados(new ArrayList<>(ofertasPorJogo.keySet()));
    return new ResultadoAtualizacaoLote(ofertasPorJogo.size(), ofertasAtualizadas);
  }

  public int salvarOfertasDoSync(List<ItemOfertaItad> itens, int deslocamento) {
    if (itens == null || itens.isEmpty()) {
      return 0;
    }

    List<RepositorioJogos.JogoParaSalvar> jogosParaSalvar = new ArrayList<>();
    for (int i = 0; i < itens.size(); i++) {
      ItemOfertaItad item = itens.get(i);
      if (item == null || item.id() == null || item.title() == null || item.deal() == null) {
        continue;
      }
      String slug = item.slug() == null || item.slug().isBlank() ? GeradorSlug.porTitulo(item.title()) : item.slug();
      String capa = item.assets() == null ? null : item.assets().banner400();
      jogosParaSalvar.add(new RepositorioJogos.JogoParaSalvar(item.id(), item.title(), slug, capa, deslocamento + i));
    }

    if (jogosParaSalvar.isEmpty()) {
      return 0;
    }

    Map<String, Long> mapaIds = jogos.salvarJogosItad(jogosParaSalvar).stream()
        .collect(Collectors.toMap(RepositorioJogos.IdJogoItad::itadId, RepositorioJogos.IdJogoItad::id));

    List<String> idsItad = new ArrayList<>(mapaIds.keySet());
    List<ResultadoPrecoItad> resultados = itad.buscarPrecos(idsItad);
    if (resultados == null || resultados.isEmpty()) {
      throw new IllegalStateException("A ITAD nao retornou precos para o lote do sync");
    }

    int atualizadas = 0;
    for (ResultadoPrecoItad resultado : resultados) {
      if (resultado == null || resultado.id() == null) {
        continue;
      }
      Long jogoId = mapaIds.get(resultado.id());
      if (jogoId == null) {
        continue;
      }

      List<OfertaParaSalvar> ofertasAtuais = new ArrayList<>();
      List<OfertaPrecoItad> ofertasItad = resultado.deals();
      if (ofertasItad != null) {
        for (OfertaPrecoItad oferta : ofertasItad) {
          if (oferta == null
              || oferta.shop() == null
              || oferta.shop().name() == null || oferta.shop().name().isBlank()
              || oferta.price() == null || oferta.price().amount() == null
              || oferta.url() == null || oferta.url().isBlank()) {
            continue;
          }
          ofertasAtuais.add(new OfertaParaSalvar(
              jogoId,
              "itad",
              oferta.shop().name(),
              oferta.price().amount(),
              oferta.regular() == null ? null : oferta.regular().amount(),
              "BRL",
              oferta.url()));
        }
      }
      atualizadas += jogos.substituirOfertasItad(jogoId, ofertasAtuais);
    }

    return atualizadas;
  }

  public int preencherMetadadosSteam(int limite) {
    List<JogoSteamPendente> pendentes = jogos.listarPendentesSteam(limite);
    int atualizados = 0;

    for (JogoSteamPendente pendente : pendentes) {
      var appId = steam.resolverAppIdSteam(pendente.url());
      var detalhes = appId.flatMap(steam::buscarDetalhesAplicativo);
      boolean ehDlc = Boolean.TRUE.equals(detalhes.map(DetalhesAplicativoSteam::ehDlc).orElse(null))
          || steam.tituloPareceDlc(pendente.titulo());
      String capa = detalhes.map(DetalhesAplicativoSteam::imagemCabecalho).orElse(null);
      Integer steamAppId = appId.map(Integer::parseInt).orElse(null);
      jogos.atualizarMetadadosSteam(pendente.id(), ehDlc, capa, steamAppId);
      atualizados++;
    }

    return atualizados;
  }

  // Descricao/generos/reviews vem da mesma chamada appdetails ja usada acima; so persistimos mais campos dela.
  public int preencherDetalhesJogos(int limite) {
    List<JogoDetalhesPendente> pendentes = jogos.listarPendentesDetalhes(limite);
    int atualizados = 0;

    for (JogoDetalhesPendente pendente : pendentes) {
      String appId = String.valueOf(pendente.steamAppId());
      var detalhes = steam.buscarDetalhesAplicativo(appId);
      var reviews = steam.buscarReviews(appId);
      if (detalhes.isEmpty() && reviews.isEmpty()) {
        continue;
      }

      List<DetalhesJogo.DestaqueJogo> destaques = detalhes.map(DetalhesAplicativoSteam::destaques).stream()
          .flatMap(List::stream)
          .map(destaque -> new DetalhesJogo.DestaqueJogo(destaque.titulo(), destaque.texto()))
          .toList();

      jogos.salvarDetalhesJogo(new DetalhesParaSalvar(
          pendente.id(),
          detalhes.map(DetalhesAplicativoSteam::descricaoCurta).orElse(null),
          detalhes.map(DetalhesAplicativoSteam::generos).orElse(null),
          detalhes.map(DetalhesAplicativoSteam::desenvolvedores).orElse(null),
          detalhes.map(DetalhesAplicativoSteam::publicadoras).orElse(null),
          detalhes.map(DetalhesAplicativoSteam::dataLancamento).orElse(null),
          detalhes.map(DetalhesAplicativoSteam::screenshots).orElse(null),
          reviews.map(ReviewsSteam::descricaoNota).orElse(null),
          reviews.map(ReviewsSteam::positivas).orElse(null),
          reviews.map(ReviewsSteam::negativas).orElse(null),
          detalhes.map(DetalhesAplicativoSteam::trailerUrl).orElse(null),
          detalhes.map(DetalhesAplicativoSteam::trailerThumbnail).orElse(null),
          detalhes.map(DetalhesAplicativoSteam::sobreCompleto).orElse(null),
          destaques,
          detalhes.map(DetalhesAplicativoSteam::categorias).orElse(null),
          detalhes.map(DetalhesAplicativoSteam::requisitosMinimos).orElse(null),
          detalhes.map(DetalhesAplicativoSteam::requisitosRecomendados).orElse(null)));
      atualizados++;
    }

    return atualizados;
  }

  // Schema (nomes/descricoes/icones) muda raramente; percentual global e' mesclado na mesma passada.
  public int preencherConquistas(int limite) {
    List<JogoDetalhesPendente> pendentes = jogos.listarPendentesConquistas(limite);
    int atualizados = 0;

    for (JogoDetalhesPendente pendente : pendentes) {
      String appId = String.valueOf(pendente.steamAppId());
      var esquema = steam.buscarEsquemaConquistas(appId);
      if (esquema.isEmpty()) {
        continue;
      }
      var percentuais = steam.buscarPercentuaisGlobais(appId);

      List<ConquistaParaSalvar> conquistas = esquema.stream()
          .map(conquista -> new ConquistaParaSalvar(
              conquista.nome(),
              conquista.tituloExibicao(),
              conquista.descricao(),
              conquista.iconeUrl(),
              conquista.iconeCinzaUrl(),
              percentuais.get(conquista.nome())))
          .toList();

      jogos.salvarConquistas(pendente.id(), conquistas);
      atualizados++;
    }

    return atualizados;
  }

  private void atualizarMetadadosSteamSeNecessario(JogoParaAtualizar jogo, List<OfertaPrecoItad> ofertas) {
    if (jogo.capaUrl() != null && jogo.ehDlc() != null) {
      return;
    }

    OfertaPrecoItad ofertaSteam = ofertas.stream()
        .filter(oferta -> oferta.shop() != null && "Steam".equals(oferta.shop().name()))
        .findFirst()
        .orElse(null);
    if (ofertaSteam == null) {
      return;
    }

    var appId = steam.resolverAppIdSteam(ofertaSteam.url());
    var detalhes = appId.flatMap(steam::buscarDetalhesAplicativo);

    Boolean ehDlc = null;
    String capa = null;
    if (jogo.ehDlc() == null) {
      ehDlc = Boolean.TRUE.equals(detalhes.map(DetalhesAplicativoSteam::ehDlc).orElse(null))
          || steam.tituloPareceDlc(jogo.titulo());
    }
    if (jogo.capaUrl() == null) {
      capa = detalhes.map(DetalhesAplicativoSteam::imagemCabecalho).orElse(null);
    }

    Integer steamAppId = appId.map(Integer::parseInt).orElse(null);
    if (ehDlc != null || capa != null || steamAppId != null) {
      jogos.atualizarMetadadosSteam(jogo.id(), ehDlc, capa, steamAppId);
    }
  }

  public static class BuscaCurtaException extends RuntimeException {}
  public static class JogoNaoEncontradoException extends RuntimeException {}
  public static class JogoSemItadException extends RuntimeException {}
  public record ResultadoAtualizacaoLote(int jogosAtualizados, int ofertasAtualizadas) {}
}
