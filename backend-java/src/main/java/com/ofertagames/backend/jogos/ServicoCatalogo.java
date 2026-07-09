package com.ofertagames.backend.jogos;

import com.ofertagames.backend.comum.GeradorSlug;
import com.ofertagames.backend.itad.ClienteItad;
import com.ofertagames.backend.itad.ItemOfertaItad;
import com.ofertagames.backend.itad.OfertaPrecoItad;
import com.ofertagames.backend.itad.ResultadoBuscaItad;
import com.ofertagames.backend.itad.ResultadoPrecoItad;
import com.ofertagames.backend.steam.DetalhesAplicativoSteam;
import com.ofertagames.backend.steam.ServicoSteam;
import java.util.ArrayList;
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

  ServicoCatalogo(RepositorioJogos jogos, ClienteItad itad, ServicoSteam steam) {
    this.jogos = jogos;
    this.itad = itad;
    this.steam = steam;
  }

  public List<ResumoJogo> buscarComFallbackItad(String busca) {
    String termo = busca == null ? "" : busca.trim();
    if (termo.length() < 2) {
      throw new BuscaCurtaException();
    }

    List<ResumoJogo> locais = jogos.listar(0, 20, "rank", "all", null, null, termo);
    if (!locais.isEmpty()) {
      return locais;
    }

    List<ResultadoBuscaItad> encontrados = Objects.requireNonNullElse(itad.buscarJogos(termo), List.of());
    if (encontrados.isEmpty()) {
      return List.of();
    }

    for (ResultadoBuscaItad jogo : encontrados) {
      String slug = jogo.slug() == null || jogo.slug().isBlank() ? GeradorSlug.porTitulo(jogo.title()) : jogo.slug();
      String capa = jogo.assets() == null ? null : jogo.assets().banner400();
      jogos.salvarJogoItad(jogo.id(), jogo.title(), slug, capa, null);
    }

    return jogos.listarPorItadIds(encontrados.stream().map(ResultadoBuscaItad::id).toList());
  }

  public ResultadoAtualizacaoJogo atualizarPrecos(String slug) {
    JogoParaAtualizar jogo = jogos.buscarParaAtualizar(slug).orElseThrow(JogoNaoEncontradoException::new);
    if (jogo.itadId() == null || jogo.itadId().isBlank()) {
      throw new JogoSemItadException();
    }

    List<ResultadoPrecoItad> resultados = Objects.requireNonNullElse(itad.buscarPrecos(jogo.itadId()), List.of());
    ResultadoPrecoItad resultado = resultados.isEmpty() ? null : resultados.get(0);
    if (resultado == null || resultado.deals() == null || resultado.deals().isEmpty()) {
      return new ResultadoAtualizacaoJogo(true, 0);
    }

    int atualizadas = 0;
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
    return new ResultadoAtualizacaoJogo(true, atualizadas);
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

    List<OfertaParaSalvar> ofertasParaSalvar = new ArrayList<>();
    for (ItemOfertaItad item : itens) {
      if (item == null || item.id() == null || item.deal() == null || item.deal().shop() == null || item.deal().price() == null || item.deal().url() == null) {
        continue;
      }
      Long jogoId = mapaIds.get(item.id());
      if (jogoId == null) {
        continue;
      }
      ofertasParaSalvar.add(new OfertaParaSalvar(
          jogoId,
          "itad",
          item.deal().shop().name(),
          item.deal().price().amount(),
          item.deal().regular() == null ? null : item.deal().regular().amount(),
          "BRL",
          item.deal().url()));
    }

    if (ofertasParaSalvar.isEmpty()) {
      return 0;
    }

    return jogos.salvarOfertas(ofertasParaSalvar).length;
  }

  public int preencherMetadadosSteam(int limite) {
    List<JogoSteamPendente> pendentes = jogos.listarPendentesSteam(limite);
    int atualizados = 0;

    for (JogoSteamPendente pendente : pendentes) {
      var appId = steam.resolverAppIdSteam(pendente.url());
      var detalhes = appId.flatMap(steam::buscarDetalhesAplicativo);
      Boolean ehDlc = detalhes.map(DetalhesAplicativoSteam::ehDlc).orElseGet(() -> steam.tituloPareceDlc(pendente.titulo()));
      String capa = detalhes.map(DetalhesAplicativoSteam::imagemCabecalho).orElse(null);
      jogos.atualizarMetadadosSteam(pendente.id(), ehDlc, capa);
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
      ehDlc = detalhes.map(DetalhesAplicativoSteam::ehDlc).orElseGet(() -> steam.tituloPareceDlc(jogo.titulo()));
    }
    if (jogo.capaUrl() == null) {
      capa = detalhes.map(DetalhesAplicativoSteam::imagemCabecalho).orElse(null);
    }

    if (ehDlc != null || capa != null) {
      jogos.atualizarMetadadosSteam(jogo.id(), ehDlc, capa);
    }
  }

  public static class BuscaCurtaException extends RuntimeException {}
  public static class JogoNaoEncontradoException extends RuntimeException {}
  public static class JogoSemItadException extends RuntimeException {}
}