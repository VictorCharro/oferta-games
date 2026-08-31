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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Orquestra o catalogo: busca com fallback na ITAD, atualizacao de preco (manual e agendada) e os
 * tres passos de enriquecimento vindos da Steam.
 *
 * <p>Os passos da Steam tem <b>ordem obrigatoria</b>, porque cada um depende do anterior:
 *
 * <ol>
 *   <li>{@link #preencherMetadadosSteam} — resolve {@code games.steam_app_id} (alem de capa e
 *       {@code is_dlc});</li>
 *   <li>{@link #preencherDetalhesJogos} — precisa do {@code steam_app_id} ja resolvido;</li>
 *   <li>{@link #preencherConquistas} — idem.</li>
 * </ol>
 *
 * <p>Consequencia: jogo sem oferta Steam nunca ganha {@code steam_app_id} e portanto fica
 * permanentemente sem as abas Sobre/Review/Conquistas. E limitacao conhecida, nao bug.
 *
 * <p>Os metodos {@code preencher*} sao os que os jobs agendados chamam e processam um lote da
 * fila; {@link #preencherTudoDoJogo} faz os tres de uma vez para um jogo so (botao de admin).
 */
@Service
public class ServicoCatalogo {
  // POST /api/games/{slug}/refresh nao exige login (qualquer visitante ve o botao "Atualizar
  // precos"); este cooldown por jogo e o unico freio contra alguem martelando o botao.
  private static final Duration COOLDOWN_REFRESH_MANUAL = Duration.ofMinutes(5);

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

  /**
   * Busca no catalogo local e, so quando nao ha nenhum resultado, consulta a ITAD.
   *
   * <p><b>Efeito colateral:</b> os jogos encontrados na ITAD sao <b>persistidos no catalogo</b>
   * antes de retornar. E por isso que buscar por um jogo inexistente faz ele passar a existir aqui
   * — e um dos caminhos pelos quais o catalogo cresce, junto da coleta agendada.
   *
   * <p>Os jogos recem-salvos entram sem preco: so ganham oferta quando a sincronizacao passar por
   * eles.
   *
   * @param busca termo com no minimo 2 caracteres uteis (apos {@code trim})
   * @return resultados locais quando houver; senao os recem-importados da ITAD; senao lista vazia
   * @throws BuscaCurtaException quando o termo tem menos de 2 caracteres
   */
  public List<ResumoJogo> buscarComFallbackItad(String busca) {
    String termo = busca == null ? "" : busca.trim();
    if (termo.length() < 2) {
      throw new BuscaCurtaException();
    }

    List<ResumoJogo> locais = jogos.listar(0, 20, "rank", "all", "all", null, null, null, termo, List.of());
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

  /**
   * Atualiza na hora os precos de um jogo (botao "Atualizar precos"), incluindo os das DLCs dele.
   *
   * <p>O endpoint e publico, entao o unico freio e o cooldown de 5 min por jogo — que vale so pro
   * <b>jogo principal</b>: as DLCs sao atualizadas junto sem consumir/checar cooldown proprio.
   * Atualizar o jogo base ja cobre as DLCs pra o usuario nao ter que repetir a acao em cada uma.
   *
   * <p>Uma DLC sem fonte de preco e ignorada em silencio; so a ausencia de fonte no jogo principal
   * derruba a operacao.
   *
   * @return total de ofertas gravadas somando jogo base e DLCs
   * @throws JogoNaoEncontradoException slug inexistente
   * @throws RefreshRecenteException ainda dentro do cooldown; carrega os segundos restantes, que
   *     viram {@code Retry-After} e a contagem regressiva no botao
   * @throws JogoSemItadException o jogo principal nao tem ITAD nem Instant Gaming
   */
  public ResultadoAtualizacaoJogo atualizarPrecos(String slug) {
    JogoParaAtualizar jogo = jogos.buscarParaAtualizar(slug).orElseThrow(JogoNaoEncontradoException::new);

    if (jogo.ultimoRefreshManual() != null) {
      Duration decorrido = Duration.between(jogo.ultimoRefreshManual(), Instant.now());
      if (decorrido.compareTo(COOLDOWN_REFRESH_MANUAL) < 0) {
        long restanteSegundos = COOLDOWN_REFRESH_MANUAL.minus(decorrido).toSeconds() + 1;
        throw new RefreshRecenteException(restanteSegundos);
      }
    }
    jogos.marcarRefreshManual(jogo.id());

    int atualizadas = atualizarPrecosDoJogo(jogo, true);

    // As DLCs sao entradas proprias do catalogo (ver "Plataformas e DLCs" em doc.md); atualizar o
    // jogo base deve atualizar o preco delas junto, pra quem clica "Atualizar precos" nao precisar
    // repetir a acao em cada DLC. Busca todas de uma vez (evita 1 query por DLC).
    List<String> slugsDlcs = jogos.listarDlcsDoJogo(jogo.id()).stream().map(ResumoJogo::slug).toList();
    for (JogoParaAtualizar jogoDlc : jogos.buscarParaAtualizarPorSlugs(slugsDlcs)) {
      atualizadas += atualizarPrecosDoJogo(jogoDlc, false);
    }

    return new ResultadoAtualizacaoJogo(true, atualizadas);
  }

  /**
   * Busca preco de um jogo nas duas fontes (ITAD e Instant Gaming) e grava o resultado.
   *
   * @param obrigatorio {@code true} exige que ao menos uma fonte tenha respondido — usado pro jogo
   *     principal do refresh. {@code false} (usado pras DLCs) ignora a ausencia de fonte em
   *     silencio, pra uma DLC sem preco nao derrubar o refresh do jogo base
   * @throws JogoSemItadException so quando {@code obrigatorio} e nenhuma fonte trouxe preco
   */
  private int atualizarPrecosDoJogo(JogoParaAtualizar jogo, boolean obrigatorio) {
    boolean temItad = jogo.itadId() != null && !jogo.itadId().isBlank();

    int atualizadas = 0;
    if (temItad) {
      List<ResultadoPrecoItad> resultados = Objects.requireNonNullElse(itad.buscarPrecos(jogo.itadId()), List.of());
      ResultadoPrecoItad resultado = resultados.isEmpty() ? null : resultados.get(0);
      if (resultado != null && resultado.deals() != null) {
        List<OfertaParaSalvar> ofertasParaSalvar = new ArrayList<>();
        for (OfertaPrecoItad oferta : resultado.deals()) {
          if (oferta.shop() == null || oferta.price() == null || oferta.url() == null) {
            continue;
          }

          ofertasParaSalvar.add(new OfertaParaSalvar(
              jogo.id(),
              "itad",
              oferta.shop().name(),
              oferta.price().amount(),
              oferta.regular() == null ? null : oferta.regular().amount(),
              "BRL",
              oferta.url(),
              oferta.voucher()));
        }
        atualizadas += jogos.salvarOfertas(ofertasParaSalvar);
        atualizarMetadadosSteamSeNecessario(jogo, resultado.deals());
      }
    }
    boolean instantGamingAtualizado = instantGaming.atualizarPrecoImediato(jogo.id(), jogo.titulo());
    if (instantGamingAtualizado) {
      atualizadas++;
    }

    if (obrigatorio && !temItad && !instantGamingAtualizado) {
      throw new JogoSemItadException();
    }
    return atualizadas;
  }

  /**
   * Caminho da coleta agendada: busca precos de varios jogos numa chamada so a ITAD e substitui as
   * ofertas do lote inteiro.
   *
   * <p><b>Efeito colateral:</b> compara o menor preco antes e depois de gravar e cria notificacao
   * de queda pra quem monitora o jogo. A comparacao usa {@link RepositorioJogos#precosMinimos},
   * que nao filtra lojas bloqueadas — ver o Javadoc de la. Ao final marca todos como
   * sincronizados, o que os manda pro fim da fila.
   *
   * <p>Jogo do lote que a ITAD nao reconhecer e simplesmente ignorado; jogo reconhecido mas sem
   * nenhuma oferta valida tem as ofertas ITAD apagadas (ver
   * {@link RepositorioJogos#substituirOfertasItadEmLote}).
   *
   * @throws IllegalStateException quando a ITAD nao devolve nada ou nada reconhecivel — falha o
   *     lote inteiro de proposito, pra o job registrar erro em vez de marcar como sincronizado um
   *     lote que nao foi atualizado
   */
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
              oferta.url(),
              oferta.voucher()));
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
              oferta.url(),
              oferta.voucher()));
        }
      }
      atualizadas += jogos.substituirOfertasItad(jogoId, ofertasAtuais);
    }

    return atualizadas;
  }

  public int preencherMetadadosSteam(int limite) {
    int atualizados = 0;
    for (JogoSteamPendente pendente : jogos.listarPendentesSteam(limite)) {
      if (processarMetadadosSteam(pendente, false)) atualizados++;
    }
    return atualizados;
  }

  /**
   * Resolve o app id da Steam pela URL da oferta e grava capa, {@code is_dlc} e
   * {@code steam_app_id}.
   *
   * <p>{@code is_dlc} sai do OR entre o sinal da Steam e a heuristica de titulo: a Steam classifica
   * trilha sonora como {@code music}, entao depender so dela deixava esses itens como jogo base.
   *
   * @param forcar {@code false} (lote agendado) preenche so o que falta;
   *     {@code true} (admin) sobrescreve sempre que a Steam devolver algo, pra corrigir dado errado
   * @return sempre {@code true} — grava mesmo quando a Steam nao devolve nada, pra tirar o jogo da
   *     fila e nao ficar reconsultando pra sempre quem simplesmente nao tem pagina na Steam
   */
  private boolean processarMetadadosSteam(JogoSteamPendente pendente, boolean forcar) {
    var appId = steam.resolverAppIdSteam(pendente.url());
    var detalhes = appId.flatMap(steam::buscarDetalhesAplicativo);
    boolean ehDlc = Boolean.TRUE.equals(detalhes.map(DetalhesAplicativoSteam::ehDlc).orElse(null))
        || steam.tituloPareceDlc(pendente.titulo());
    String capa = detalhes.map(DetalhesAplicativoSteam::imagemCabecalho).orElse(null);
    Integer steamAppId = appId.map(Integer::parseInt).orElse(null);
    if (forcar) {
      jogos.forcarMetadadosSteam(pendente.id(), ehDlc, capa, steamAppId);
    } else {
      jogos.atualizarMetadadosSteam(pendente.id(), ehDlc, capa, steamAppId);
    }
    return true;
  }

  // Descricao/generos/reviews vem da mesma chamada appdetails ja usada acima; so persistimos mais campos dela.
  public int preencherDetalhesJogos(int limite) {
    int atualizados = 0;
    for (JogoDetalhesPendente pendente : jogos.listarPendentesDetalhes(limite)) {
      if (processarDetalhesJogo(pendente)) atualizados++;
    }
    return atualizados;
  }

  /**
   * Salva descricao, generos, midia, requisitos, resumo de reviews e a lista de DLCs de um jogo.
   *
   * <p>Exige {@code steam_app_id} ja resolvido. O array {@code dlc} salvo aqui em
   * {@code game_details.dlc_steam_app_ids} e o que alimenta as duas direcoes de DLC na pagina do
   * jogo ({@link RepositorioJogos#listarDlcsDoJogo} e
   * {@link RepositorioJogos#listarJogosBaseDaDlc}).
   *
   * @return {@code false} quando nem detalhes nem reviews vieram — nesse caso nada e gravado e o
   *     jogo <b>continua na fila</b> pra ser tentado de novo
   */
  private boolean processarDetalhesJogo(JogoDetalhesPendente pendente) {
    String appId = String.valueOf(pendente.steamAppId());
    var detalhes = steam.buscarDetalhesAplicativo(appId);
    var reviews = steam.buscarReviews(appId);
    if (detalhes.isEmpty() && reviews.isEmpty()) {
      return false;
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
        detalhes.map(DetalhesAplicativoSteam::requisitosRecomendados).orElse(null),
        detalhes.map(DetalhesAplicativoSteam::dlcAppIds).orElse(null)));
    return true;
  }

  // Schema (nomes/descricoes/icones) muda raramente; percentual global e' mesclado na mesma passada.
  public int preencherConquistas(int limite) {
    int atualizados = 0;
    for (JogoDetalhesPendente pendente : jogos.listarPendentesConquistas(limite)) {
      if (processarConquistas(pendente)) atualizados++;
    }
    return atualizados;
  }

  /**
   * Salva o catalogo de conquistas do jogo, mesclando o esquema da Steam com o percentual global
   * de cada uma.
   *
   * <p><b>Cuidado com o retorno:</b> quando o esquema vem vazio (jogo sem conquista nenhuma) este
   * metodo devolve {@code false} <b>mas mesmo assim trabalhou</b> — ele chama
   * {@link RepositorioJogos#marcarConquistasVerificadas}, que e o que tira o jogo da fila.
   * Portanto "0 atualizados" numa rodada nao significa que nada avancou.
   *
   * <p>Essa marcacao e essencial: sem ela os jogos sem conquista voltavam pra fila pra sempre e
   * travavam o backlog (bug corrigido em 09/08/2026).
   *
   * @return {@code true} so quando havia conquistas de fato pra gravar
   */
  private boolean processarConquistas(JogoDetalhesPendente pendente) {
    String appId = String.valueOf(pendente.steamAppId());
    var esquema = steam.buscarEsquemaConquistas(appId);
    if (esquema.isEmpty()) {
      jogos.marcarConquistasVerificadas(pendente.id());
      return false;
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
    return true;
  }

  /**
   * Roda os tres passos da Steam para um unico jogo, na hora — botao de admin "Preencher tudo
   * agora", pra quando nao vale esperar o jogo chegar na vez da fila (que pode levar horas).
   *
   * <p>Executa na ordem obrigatoria (metadados primeiro, pra resolver o {@code steam_app_id} de
   * que detalhes e conquistas dependem) e usa {@code forcar = true} nos metadados, entao aqui
   * <b>sobrescreve</b> capa/{@code steam_app_id} ja existentes — e o proposito: corrigir dado
   * errado.
   *
   * <p>Se mesmo depois do primeiro passo o jogo continuar sem {@code steam_app_id}, detalhes e
   * conquistas sao pulados e o resultado sai com {@code temSteamAppId = false}.
   *
   * @throws JogoNaoEncontradoException slug inexistente
   */
  public ResultadoPreenchimentoJogo preencherTudoDoJogo(String slug) {
    long jogoId = jogos.buscarIdPorSlug(slug).orElseThrow(JogoNaoEncontradoException::new);

    boolean metadadosAtualizados = jogos.buscarJogoParaMetadadosSteam(jogoId)
        .map(pendente -> processarMetadadosSteam(pendente, true))
        .orElse(false);

    Integer steamAppId = jogos.buscarSteamAppId(jogoId).orElse(null);
    boolean detalhesAtualizados = false;
    boolean conquistasAtualizadas = false;
    if (steamAppId != null) {
      JogoDetalhesPendente pendente = new JogoDetalhesPendente(jogoId, steamAppId);
      detalhesAtualizados = processarDetalhesJogo(pendente);
      conquistasAtualizadas = processarConquistas(pendente);
    }

    return new ResultadoPreenchimentoJogo(metadadosAtualizados, steamAppId != null, detalhesAtualizados, conquistasAtualizadas);
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

  public static class RefreshRecenteException extends RuntimeException {
    private final long segundosRestantes;

    RefreshRecenteException(long segundosRestantes) {
      this.segundosRestantes = segundosRestantes;
    }

    public long segundosRestantes() {
      return segundosRestantes;
    }
  }
  public record ResultadoAtualizacaoLote(int jogosAtualizados, int ofertasAtualizadas) {}

  public record ResultadoPreenchimentoJogo(
      boolean metadadosSteamAtualizados,
      boolean temSteamAppId,
      boolean detalhesAtualizados,
      boolean conquistasAtualizadas) {}
}
