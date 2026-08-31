package com.ofertagames.backend.jogos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ofertagames.backend.comum.ClassificadorDlc;
import com.ofertagames.backend.comum.ConfiguracaoCache;
import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import com.ofertagames.backend.comum.LojasBloqueadas;
import com.ofertagames.backend.comum.LojasCatalogo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acesso SQL a tudo que gira em torno de {@code games}: catalogo, detalhe, ofertas, historico de
 * preco, filas dos jobs de sincronizacao, metadados/detalhes/conquistas da Steam e sitemap.
 *
 * <p>E a maior classe do backend e concentra responsabilidades demais pra um repositorio so —
 * dividir por assunto (catalogo / ofertas / sincronizacao / steam) e uma refatoracao desejavel,
 * mas ainda nao feita.
 *
 * <h2>Convencoes das queries</h2>
 *
 * <ul>
 *   <li>As leituras do catalogo aplicam sempre os filtros de dominio de {@code comum/}
 *       ({@link com.ofertagames.backend.comum.LojasBloqueadas},
 *       {@link com.ofertagames.backend.comum.ConteudosNaoJogos},
 *       {@link com.ofertagames.backend.comum.JogosBloqueados}). Esses filtros devolvem fragmentos
 *       que comecam com {@code AND} — e por isso que as queries abrem com {@code WHERE 1=1}.</li>
 *   <li>Menor preco nunca e armazenado: sai de {@code MIN(o.price)} em tempo de query.</li>
 *   <li>Aliases interpolados em SQL sao sempre literais do proprio codigo, nunca valor de request.</li>
 * </ul>
 */
@Repository
public class RepositorioJogos {
  private final JdbcClient jdbc;
  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  RepositorioJogos(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * Pagina do catalogo, ja com o menor preco e a loja correspondente. Usada pelo Catalogo (scroll
   * infinito), pela pagina Mais Vendidos e pela busca com filtro.
   *
   * <p><b>Custo:</b> a query junta {@code games} com {@code offers} e agrega o catalogo inteiro
   * antes de aplicar {@code LIMIT}, entao o custo e praticamente o mesmo para qualquer pagina
   * (~500ms no banco, medido com 110k jogos). O cache de 10 min
   * ({@link ConfiguracaoCache#CACHE_CATALOGO}) e o que segura isso hoje — mas a chave inclui
   * <i>todos</i> os parametros, entao cada combinacao de filtro/pagina paga o custo cheio uma vez.
   * Ver issue #3.
   *
   * <p><b>O que depende do agregado</b> (relevante pra qualquer tentativa de pre-paginar em
   * {@code games} antes do join):
   *
   * <ul>
   *   <li>{@code precoMinimo}, {@code precoMaximo} e {@code descontoMinimo} viram {@code HAVING}
   *       sobre {@code MIN(o.price)}/{@code MAX(o.regular_price)} — ver {@code filtroPreco}.</li>
   *   <li>As ordenacoes {@code discount}, {@code price_asc} e {@code price_desc} tambem, e a
   *       padrao ({@code rank}) parcialmente — ver {@code ordenarPor}.</li>
   *   <li>So {@code popularity} ordena exclusivamente por colunas de {@code games}.</li>
   * </ul>
   *
   * @param ordenacao {@code rank} (padrao: destaque com desconto primeiro, depois rank),
   *     {@code popularity}, {@code discount}, {@code price_asc} ou {@code price_desc}. Valor
   *     desconhecido cai no padrao
   * @param tipo {@code game}, {@code dlc} ou qualquer outro valor para nao filtrar
   * @param plataforma {@code pc}, {@code xbox}, {@code playstation}, ou outro valor para nao
   *     filtrar. Inferida do nome/URL da loja, nao de coluna propria
   * @param busca casa por {@code ILIKE} em qualquer posicao do titulo; nulo/vazio nao filtra
   * @param lojas chaves de {@link com.ofertagames.backend.comum.LojasCatalogo} ("lojas
   *     preferidas"). Lista vazia ou so com chaves invalidas <b>nao filtra</b> — nao devolve vazio.
   *     Quando filtra, restringe tanto quais jogos aparecem quanto quais ofertas contam pro preco
   *     exibido
   * @return no maximo {@code tamanho} itens; lista vazia quando a pagina passa do fim
   */
  @Cacheable(ConfiguracaoCache.CACHE_CATALOGO)
  public List<ResumoJogo> listar(int pagina, int tamanho, String ordenacao, String tipo, String plataforma, Double precoMinimo, Double precoMaximo, Double descontoMinimo, String busca, List<String> lojas) {
    int deslocamento = pagina * tamanho;
    String filtroTipo = switch (tipo) {
      case "dlc" -> "AND " + ClassificadorDlc.condicaoDlcSql("g");
      case "game" -> ClassificadorDlc.filtroApenasJogosSql("g");
      default -> "";
    };
    String filtroBusca = busca == null || busca.isBlank() ? "" : "AND g.title ILIKE :busca";
    String filtroPlataforma = filtroPlataforma(plataforma);
    String filtroOfertaPlataforma = filtroOfertaPlataforma(plataforma);
    String filtroLojaBloqueada = filtroLojaBloqueada("o");
    String filtroConteudoNaoJogo = ConteudosNaoJogos.filtroSql("g");
    String filtroJogoBloqueado = JogosBloqueados.filtroSql("g");
    String filtroPreco = filtroPreco(precoMinimo, precoMaximo, descontoMinimo);
    String regexLojas = LojasCatalogo.regexParaChaves(lojas);
    String filtroLojasPreferidas = filtroLojasPreferidas(regexLojas);
    String filtroOfertaLojasPreferidas = filtroOfertaLojasPreferidas(regexLojas);

    String sql = """
        SELECT
          g.slug,
          g.title,
          g.cover_url AS cover_url,
          g.is_dlc AS is_dlc,
          MIN(o.price) AS min_price,
          MAX(o.regular_price) AS regular_price,
          (ARRAY_AGG(o.store_name ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.store_name IS NOT NULL))[1] AS store_name,
          (ARRAY_AGG(o.url ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.url IS NOT NULL))[1] AS url
        FROM games g
        LEFT JOIN offers o ON o.game_id = g.id %s %s %s
        WHERE 1=1
        %s
        %s
        %s
        %s
        %s
        %s
        GROUP BY g.id, g.slug, g.title, g.cover_url, g.is_dlc
        %s
        ORDER BY %s
        LIMIT :tamanho OFFSET :deslocamento
        """.formatted(filtroOfertaPlataforma, filtroLojaBloqueada, filtroOfertaLojasPreferidas, filtroTipo, filtroBusca, filtroPlataforma, filtroLojasPreferidas, filtroConteudoNaoJogo, filtroJogoBloqueado, filtroPreco, ordenarPor(ordenacao));

    var comando = jdbc.sql(sql).param("tamanho", tamanho).param("deslocamento", deslocamento);
    if (busca != null && !busca.isBlank()) {
      comando = comando.param("busca", "%" + busca.trim() + "%");
    }
    if (precoMinimo != null) {
      comando = comando.param("precoMinimo", precoMinimo);
    }
    if (precoMaximo != null) {
      comando = comando.param("precoMaximo", precoMaximo);
    }
    if (descontoMinimo != null) {
      comando = comando.param("descontoMinimo", descontoMinimo);
    }
    if (regexLojas != null) {
      comando = comando.param("lojasRegex", regexLojas);
    }

    return comando.query(RepositorioJogos::mapearResumo).list();
  }

  public Optional<DetalheJogo> buscarPorSlug(String slug) {
    Optional<LinhaJogo> jogo = jdbc.sql("""
        SELECT id, slug, title, cover_url
        FROM games
        WHERE slug = :slug
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("games"), JogosBloqueados.filtroSql("games")))
        .param("slug", slug)
        .query((rs, linha) -> new LinhaJogo(
            rs.getLong("id"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url")))
        .optional();

    return jogo.map(linha -> new DetalheJogo(
        linha.id(), linha.slug(), linha.title(), linha.coverUrl(), listarOfertas(linha.id()),
        listarDlcsDoJogo(linha.id()), listarJogosBaseDaDlc(linha.id())));
  }

  /**
   * DLCs de um jogo base, para a secao correspondente na aba Precos.
   *
   * <p>A relacao vem do array {@code dlc} da appdetails da Steam, salvo em
   * {@code game_details.dlc_steam_app_ids} quando os detalhes do jogo base sao preenchidos. So
   * retorna DLCs que <b>ja existem no nosso catalogo</b> (descobertas via ITAD em algum momento),
   * entao a lista costuma ser menor que a da Steam.
   *
   * @return lista vazia quando o jogo base ainda nao foi detalhado ou nenhuma DLC dele esta no
   *     catalogo — nos dois casos a secao inteira fica oculta na interface
   */
  public List<ResumoJogo> listarDlcsDoJogo(long jogoId) {
    List<Integer> appIds = jdbc.sql("SELECT dlc_steam_app_ids FROM game_details WHERE game_id = :jogoId")
        .param("jogoId", jogoId)
        .query((rs, linha) -> {
          java.sql.Array array = rs.getArray("dlc_steam_app_ids");
          if (array == null) {
            return List.<Integer>of();
          }
          Object[] valores = (Object[]) array.getArray();
          List<Integer> resultado = new ArrayList<>();
          for (Object valor : valores) {
            if (valor != null) resultado.add(((Number) valor).intValue());
          }
          return resultado;
        })
        .optional()
        .orElse(List.of());
    if (appIds.isEmpty()) {
      return List.of();
    }

    return jdbc.sql("""
        SELECT
          g.slug,
          g.title,
          g.cover_url AS cover_url,
          g.is_dlc AS is_dlc,
          MIN(o.price) AS min_price,
          MAX(o.regular_price) AS regular_price,
          (ARRAY_AGG(o.store_name ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.store_name IS NOT NULL))[1] AS store_name,
          (ARRAY_AGG(o.url ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.url IS NOT NULL))[1] AS url
        FROM games g
        LEFT JOIN offers o ON o.game_id = g.id %s
        WHERE g.steam_app_id IN (:appIds)
          %s
          %s
        GROUP BY g.id, g.slug, g.title, g.cover_url, g.is_dlc
        ORDER BY g.title
        """.formatted(filtroLojaBloqueada("o"), ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .param("appIds", appIds)
        .query(RepositorioJogos::mapearResumo)
        .list();
  }

  /**
   * Caminho inverso de {@link #listarDlcsDoJogo}: dado uma DLC, os jogos base dos quais ela faz
   * parte — o que fecha a ida e volta entre as duas paginas.
   *
   * <p>Cruza o {@code steam_app_id} deste jogo contra o {@code dlc_steam_app_ids} de todos os
   * demais, entao so encontra algo depois que o <b>jogo base</b> foi detalhado. Devolve lista (nao
   * um item so) porque a mesma DLC pode constar em mais de um jogo base, como edicoes/bundles.
   */
  public List<ResumoJogo> listarJogosBaseDaDlc(long jogoId) {
    Integer steamAppId = jdbc.sql("SELECT steam_app_id FROM games WHERE id = :jogoId")
        .param("jogoId", jogoId)
        .query(Integer.class)
        .optional()
        .orElse(null);
    if (steamAppId == null) {
      return List.of();
    }

    return jdbc.sql("""
        SELECT
          g.slug,
          g.title,
          g.cover_url AS cover_url,
          g.is_dlc AS is_dlc,
          MIN(o.price) AS min_price,
          MAX(o.regular_price) AS regular_price,
          (ARRAY_AGG(o.store_name ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.store_name IS NOT NULL))[1] AS store_name,
          (ARRAY_AGG(o.url ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.url IS NOT NULL))[1] AS url
        FROM games g
        JOIN game_details gd ON gd.game_id = g.id
        LEFT JOIN offers o ON o.game_id = g.id %s
        WHERE gd.dlc_steam_app_ids @> ARRAY[:steamAppId]::integer[]
          %s
          %s
        GROUP BY g.id, g.slug, g.title, g.cover_url, g.is_dlc
        ORDER BY g.title
        """.formatted(filtroLojaBloqueada("o"), ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .param("steamAppId", steamAppId)
        .query(RepositorioJogos::mapearResumo)
        .list();
  }

  // Usado pelo sitemap.xml: precisa so do slug, paginado (o catalogo tem 100k+ jogos, acima do
  // limite de 50k URLs por arquivo de sitemap do Google).
  public long contarSlugsParaSitemap() {
    return jdbc.sql("""
        SELECT COUNT(*)
        FROM games g
        WHERE g.slug IS NOT NULL
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .query(Long.class)
        .single();
  }

  public List<String> listarSlugsParaSitemap(int pagina, int tamanho) {
    return jdbc.sql("""
        SELECT g.slug
        FROM games g
        WHERE g.slug IS NOT NULL
          %s
          %s
        ORDER BY g.id ASC
        LIMIT :tamanho OFFSET :deslocamento
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .param("tamanho", tamanho)
        .param("deslocamento", pagina * tamanho)
        .query(String.class)
        .list();
  }

  public Optional<Long> buscarIdPorSlug(String slug) {
    return jdbc.sql("SELECT id FROM games WHERE slug = :slug "
        + ConteudosNaoJogos.filtroSql("games")
        + JogosBloqueados.filtroSql("games"))
        .param("slug", slug)
        .query(Long.class)
        .optional();
  }

  public long salvarJogoItad(String itadId, String titulo, String slug, String capa, Integer rank) {
    try {
      return jdbc.sql("""
          INSERT INTO games (itad_id, title, slug, cover_url, rank)
          VALUES (CAST(:itadId AS uuid), :titulo, :slug, :capa, :rank)
          ON CONFLICT (itad_id) DO UPDATE
            SET title = EXCLUDED.title,
                cover_url = COALESCE(EXCLUDED.cover_url, games.cover_url),
                rank = COALESCE(EXCLUDED.rank, games.rank)
          RETURNING id
          """)
          .param("itadId", itadId)
          .param("titulo", titulo)
          .param("slug", slug)
          .param("capa", capa)
          .param("rank", rank)
          .query(Long.class)
          .single();
    } catch (DuplicateKeyException conflito) {
      return buscarIdPorSlug(slug).orElseThrow(() -> conflito);
    }
  }

  public List<IdJogoItad> salvarJogosItad(List<JogoParaSalvar> jogos) {
    return jogos.stream()
        .filter(jogo -> !ConteudosNaoJogos.contem(jogo.title()))
        .filter(jogo -> !JogosBloqueados.contemSlug(jogo.slug()))
        .map(jogo -> new IdJogoItad(
            salvarJogoItad(jogo.itadId(), jogo.title(), jogo.slug(), jogo.coverUrl(), jogo.rank()),
            jogo.itadId()))
        .toList();
  }

  public List<ResumoJogo> listarPorItadIds(List<String> itadIds) {
    return jdbc.sql("""
        SELECT
          g.slug,
          g.title,
          g.cover_url AS cover_url,
          g.is_dlc AS is_dlc,
          MIN(o.price) AS min_price,
          MAX(o.regular_price) AS regular_price,
          (ARRAY_AGG(o.store_name ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.store_name IS NOT NULL))[1] AS store_name,
          (ARRAY_AGG(o.url ORDER BY o.price ASC NULLS LAST) FILTER (WHERE o.url IS NOT NULL))[1] AS url
        FROM games g
        LEFT JOIN offers o ON o.game_id = g.id %s
        WHERE g.itad_id::text IN (:itadIds)
          %s
          %s
        GROUP BY g.id, g.slug, g.title, g.cover_url, g.is_dlc
        ORDER BY g.title
        """.formatted(
            filtroLojaBloqueada("o"),
            ConteudosNaoJogos.filtroSql("g"),
            JogosBloqueados.filtroSql("g")))
        .param("itadIds", itadIds)
        .query(RepositorioJogos::mapearResumo)
        .list();
  }

  // Usado pra oferecer "Ver no catalogo" nos cards da biblioteca Steam do perfil, quando o app
  // tiver uma entrada correspondente no nosso catalogo.
  public Map<Integer, String> buscarSlugsPorSteamAppIds(List<Integer> appIds) {
    if (appIds.isEmpty()) {
      return Map.of();
    }
    List<SlugPorAppId> linhas = jdbc.sql("""
        SELECT steam_app_id, slug
        FROM games
        WHERE steam_app_id IN (:appIds)
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("games"), JogosBloqueados.filtroSql("games")))
        .param("appIds", appIds)
        .query((rs, linha) -> new SlugPorAppId(rs.getInt("steam_app_id"), rs.getString("slug")))
        .list();
    Map<Integer, String> resultado = new HashMap<>();
    for (SlugPorAppId linha : linhas) {
      resultado.put(linha.steamAppId(), linha.slug());
    }
    return resultado;
  }

  private record SlugPorAppId(int steamAppId, String slug) {}

  public void salvarOferta(OfertaParaSalvar oferta) {
    if (lojaBloqueada(oferta.loja())) {
      return;
    }
    jdbc.sql("""
        INSERT INTO offers (game_id, source, store_name, price, regular_price, currency, url, voucher_code, updated_at)
        VALUES (:jogoId, :fonte, :loja, :preco, :precoNormal, :moeda, :url, :cupom, now())
        ON CONFLICT (game_id, source, store_name) DO UPDATE
          SET price = EXCLUDED.price,
              regular_price = EXCLUDED.regular_price,
              currency = EXCLUDED.currency,
              url = EXCLUDED.url,
              voucher_code = EXCLUDED.voucher_code,
              updated_at = EXCLUDED.updated_at
        """)
        .param("jogoId", oferta.jogoId())
        .param("fonte", oferta.fonte())
        .param("loja", oferta.loja())
        .param("preco", oferta.preco())
        .param("precoNormal", oferta.precoNormal())
        .param("moeda", oferta.moeda())
        .param("url", oferta.url())
        .param("cupom", oferta.cupom())
        .update();
  }

  /**
   * Grava ofertas em lote (upsert por {@code game_id + source + store_name}).
   *
   * <p>Descarta silenciosamente ofertas de loja bloqueada, entao o retorno pode ser menor que a
   * lista recebida — e uma lista so de lojas bloqueadas resulta em zero, sem erro.
   *
   * <p><b>Efeito colateral:</b> chama {@link #registrarHistoricoDePrecos} para todos os jogos
   * tocados. E por aqui que o historico de preco e alimentado nos tres caminhos que escrevem
   * oferta da ITAD (refresh manual, {@link #substituirOfertasItad} e
   * {@link #substituirOfertasItadEmLote}).
   *
   * @return quantidade de linhas afetadas no {@code offers}
   */
  public int salvarOfertas(List<OfertaParaSalvar> ofertas) {
    List<OfertaParaSalvar> ofertasPermitidas = ofertas.stream()
        .filter(oferta -> !lojaBloqueada(oferta.loja()))
        .toList();
    if (ofertasPermitidas.isEmpty()) {
      return 0;
    }

    String sql = """
        INSERT INTO offers (game_id, source, store_name, price, regular_price, currency, url, voucher_code, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (game_id, source, store_name) DO UPDATE
          SET price = EXCLUDED.price,
              regular_price = EXCLUDED.regular_price,
              currency = EXCLUDED.currency,
              url = EXCLUDED.url,
              voucher_code = EXCLUDED.voucher_code,
              updated_at = EXCLUDED.updated_at
        """;
    int[][] updateCounts = jdbcTemplate.batchUpdate(sql, ofertasPermitidas, 100, (ps, o) -> {
      ps.setLong(1, o.jogoId());
      ps.setString(2, o.fonte());
      ps.setString(3, o.loja());
      ps.setBigDecimal(4, o.preco());
      ps.setBigDecimal(5, o.precoNormal());
      ps.setString(6, o.moeda());
      ps.setString(7, o.url());
      ps.setString(8, o.cupom());
    });

    registrarHistoricoDePrecos(ofertasPermitidas.stream().map(OfertaParaSalvar::jogoId).toList());

    return Arrays.stream(updateCounts)
        .flatMapToInt(Arrays::stream)
        .map(contagem -> contagem == Statement.SUCCESS_NO_INFO ? 1 : Math.max(contagem, 0))
        .sum();
  }

  /**
   * Grava um ponto em {@code price_history} para cada jogo cujo menor preco mudou.
   *
   * <p><b>So grava em mudanca</b>, nao a cada sincronizacao: compara o {@code MIN(price)} atual
   * com a ultima linha registrada daquele jogo. Isso mantem o volume da tabela proporcional a
   * mudancas de preco e nao ao ritmo dos jobs (que tocam milhares de jogos a cada 10 min mesmo sem
   * nada ter mudado). O {@code IS DISTINCT FROM} com subquery vazia cobre a primeira linha de cada
   * jogo.
   *
   * <p>Duas consequencias que importam pra quem le o historico depois:
   *
   * <ul>
   *   <li>Um jogo com <b>um unico ponto</b> nao e um jogo sem dado — e um jogo cujo preco nunca
   *       mudou desde aquela data.</li>
   *   <li>O ultimo ponto e a ultima <b>mudanca</b>, nao o estado de hoje. Um grafico fiel precisa
   *       estender o ultimo valor ate a data atual (ver issue #4).</li>
   * </ul>
   *
   * <p>Hoje nao ha limiar minimo: variacao de centavos vinda da conversao cambial e gravada como
   * se fosse mudanca de preco (ver issue #1).
   *
   * <p>Normalmente nao e chamado direto — {@link #salvarOfertas} ja invoca.
   */
  public void registrarHistoricoDePrecos(List<Long> jogosIds) {
    if (jogosIds == null || jogosIds.isEmpty()) {
      return;
    }
    List<Long> idsUnicos = List.copyOf(new LinkedHashSet<>(jogosIds));
    // Ignora lojas bloqueadas (mesmo filtro da tabela de ofertas da pagina do jogo) - senao o
    // "menor preco" do historico podia vir de uma loja que o usuario nem consegue ver na pagina.
    jdbc.sql("""
        INSERT INTO price_history (game_id, price, store_name)
        SELECT atual.game_id, atual.preco, atual.loja
        FROM (
          SELECT DISTINCT ON (o.game_id) o.game_id, o.price AS preco, o.store_name AS loja
          FROM offers o
          WHERE o.game_id IN (:jogosIds)
            %s
          ORDER BY o.game_id, o.price ASC
        ) atual
        WHERE atual.preco IS DISTINCT FROM (
          SELECT ph.price FROM price_history ph
          WHERE ph.game_id = atual.game_id
          ORDER BY ph.captured_at DESC
          LIMIT 1
        )
        """.formatted(LojasBloqueadas.filtroSql("o")))
        .param("jogosIds", idsUnicos)
        .update();
  }

  /**
   * Pontos do historico em ordem cronologica (mais antigo primeiro), ja no formato que o grafico
   * da pagina do jogo espera.
   *
   * <p>Cada ponto e uma <b>mudanca</b> de preco, nao uma amostragem periodica: os intervalos entre
   * pontos sao irregulares e o ultimo ponto costuma ser mais antigo que hoje. Ver
   * {@link #registrarHistoricoDePrecos}.
   *
   * @param dias janela pra tras a partir de agora; o filtro e por
   *     {@code captured_at >= now() - dias}
   * @return lista vazia quando o jogo nao tem historico na janela — nunca {@code null}
   */
  public List<PontoHistoricoPreco> listarHistoricoDePrecos(long jogoId, int dias) {
    return jdbc.sql("""
        SELECT price, store_name, captured_at
        FROM price_history
        WHERE game_id = :jogoId AND captured_at >= now() - make_interval(days => :dias)
        ORDER BY captured_at ASC
        """)
        .param("jogoId", jogoId)
        .param("dias", dias)
        .query((rs, linha) -> new PontoHistoricoPreco(
            rs.getBigDecimal("price"),
            rs.getString("store_name"),
            rs.getTimestamp("captured_at").toInstant()))
        .list();
  }

  /**
   * Apaga pontos de historico mais antigos que a retencao. Roda diariamente pelo
   * {@code AgendadorColetas}.
   *
   * <p>Perda de dado e definitiva e nao ha backup do historico — reduzir a retencao descarta o
   * passado na primeira execucao seguinte.
   *
   * @return quantidade de linhas removidas
   */
  public int podarHistoricoDePrecos(int diasRetencao) {
    return jdbc.sql("DELETE FROM price_history WHERE captured_at < now() - make_interval(days => :dias)")
        .param("dias", diasRetencao)
        .update();
  }

  public record PontoHistoricoPreco(BigDecimal price, String lojaNome, java.time.Instant capturadoEm) {}

  /**
   * Troca as ofertas ITAD de um jogo pelas recebidas, removendo as que a ITAD nao devolve mais.
   *
   * <p>O {@code DELETE} e restrito a {@code source = 'itad'}: ofertas de outras fontes (hoje
   * {@code instant_gaming}) sobrevivem. Transacional, entao nunca deixa o jogo sem oferta nenhuma
   * caso a insercao falhe.
   */
  @Transactional
  public int substituirOfertasItad(long jogoId, List<OfertaParaSalvar> ofertas) {
    jdbc.sql("DELETE FROM offers WHERE game_id = :jogoId AND source = 'itad'")
        .param("jogoId", jogoId)
        .update();
    return salvarOfertas(ofertas);
  }

  /**
   * Versao em lote de {@link #substituirOfertasItad}, usada pela coleta agendada: um unico
   * {@code DELETE} e um unico batch de insercao para todos os jogos da rodada.
   *
   * <p>Cuidado ao chamar: um jogo presente no mapa com lista vazia tem as ofertas ITAD apagadas e
   * nenhuma inserida no lugar — e assim que um jogo delistado perde as ofertas.
   */
  @Transactional
  public int substituirOfertasItadEmLote(Map<Long, List<OfertaParaSalvar>> ofertasPorJogo) {
    if (ofertasPorJogo.isEmpty()) {
      return 0;
    }
    List<Long> jogosIds = List.copyOf(ofertasPorJogo.keySet());
    jdbc.sql("DELETE FROM offers WHERE source = 'itad' AND game_id IN (:jogosIds)")
        .param("jogosIds", jogosIds)
        .update();
    List<OfertaParaSalvar> ofertas = ofertasPorJogo.values().stream()
        .flatMap(List::stream)
        .toList();
    return salvarOfertas(ofertas);
  }

  /**
   * Menor preco atual de varios jogos de uma vez. Usado por {@code ServicoCatalogo} antes e depois
   * de gravar as ofertas, pra comparar e decidir se houve queda (ver
   * {@code RepositorioNotificacoes.registrarQueda}).
   *
   * <p><b>Atencao:</b> diferente de todas as leituras do catalogo, esta query <b>nao</b> aplica
   * {@link com.ofertagames.backend.comum.LojasBloqueadas}. Entao o preco comparado aqui pode vir de
   * uma loja que o usuario nunca ve na interface, o que gera notificacao de queda que nao bate com
   * o preco exibido na tela.
   *
   * @return mapa so com os jogos que tem ao menos uma oferta; jogo sem oferta fica ausente (nao
   *     vem com valor nulo)
   */
  public Map<Long, BigDecimal> precosMinimos(List<Long> jogosIds) {
    if (jogosIds == null || jogosIds.isEmpty()) return Map.of();
    Map<Long, BigDecimal> precos = new HashMap<>();
    jdbc.sql("SELECT game_id, MIN(price) AS preco FROM offers WHERE game_id IN (:jogosIds) GROUP BY game_id")
        .param("jogosIds", jogosIds)
        .query((rs, linha) -> new PrecoMinimo(rs.getLong("game_id"), rs.getBigDecimal("preco")))
        .list()
        .forEach(preco -> precos.put(preco.jogoId(), preco.preco()));
    return precos;
  }

  public List<JogoParaSincronizar> listarParaSincronizar(int limiteRelevantes, int limiteGerais) {
    return jdbc.sql("""
        WITH relevantes AS (
          SELECT id, itad_id::text AS itad_id
          FROM games
          WHERE itad_id IS NOT NULL AND rank IS NOT NULL AND rank <= 2000
            %s
            %s
          ORDER BY last_price_sync_at ASC NULLS FIRST, rank ASC, id ASC
          LIMIT :limiteRelevantes
        ), gerais AS (
          SELECT id, itad_id::text AS itad_id
          FROM games
          WHERE itad_id IS NOT NULL AND (rank IS NULL OR rank > 2000)
            %s
            %s
          ORDER BY last_price_sync_at ASC NULLS FIRST, rank ASC NULLS LAST, id ASC
          LIMIT :limiteGerais
        )
        SELECT id, itad_id FROM relevantes
        UNION ALL
        SELECT id, itad_id FROM gerais
        """.formatted(
            ConteudosNaoJogos.filtroSql("games"),
            JogosBloqueados.filtroSql("games"),
            ConteudosNaoJogos.filtroSql("games"),
            JogosBloqueados.filtroSql("games")))
        .param("limiteRelevantes", limiteRelevantes)
        .param("limiteGerais", limiteGerais)
        .query((rs, linha) -> new JogoParaSincronizar(rs.getLong("id"), rs.getString("itad_id")))
        .list();
  }

  public void marcarPrecosSincronizados(List<Long> jogosIds) {
    if (jogosIds.isEmpty()) {
      return;
    }
    jdbc.sql("UPDATE games SET last_price_sync_at = now() WHERE id IN (:jogosIds)")
        .param("jogosIds", jogosIds)
        .update();
  }

  public ResumoFilaColeta resumirFilaColeta() {
    return jdbc.sql("""
        SELECT
          COUNT(*) FILTER (WHERE last_price_sync_at IS NULL) AS nunca_sincronizados,
          MIN(last_price_sync_at)::text AS sincronizacao_mais_antiga,
          COUNT(*) FILTER (WHERE is_dlc IS NULL OR cover_url IS NULL) AS pendentes_steam
        FROM games
        WHERE itad_id IS NOT NULL
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("games"), JogosBloqueados.filtroSql("games")))
        .query((rs, linha) -> new ResumoFilaColeta(
            rs.getLong("nunca_sincronizados"),
            rs.getString("sincronizacao_mais_antiga"),
            rs.getLong("pendentes_steam")))
        .single();
  }

  public Optional<JogoParaAtualizar> buscarParaAtualizar(String slug) {
    return jdbc.sql("""
        SELECT id, title, itad_id::text AS itad_id, cover_url, is_dlc, instant_gaming_url, last_manual_refresh_at
        FROM games
        WHERE slug = :slug
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("games"), JogosBloqueados.filtroSql("games")))
        .param("slug", slug)
        .query((rs, linha) -> new JogoParaAtualizar(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("itad_id"),
            rs.getString("cover_url"),
            rs.getObject("is_dlc", Boolean.class),
            rs.getString("instant_gaming_url"),
            rs.getTimestamp("last_manual_refresh_at") == null ? null : rs.getTimestamp("last_manual_refresh_at").toInstant()))
        .optional();
  }

  // Batch de buscarParaAtualizar por slug, usado pelo refresh das DLCs de um jogo: evita 1 query
  // por DLC (N+1) quando o jogo tem varias.
  public List<JogoParaAtualizar> buscarParaAtualizarPorSlugs(List<String> slugs) {
    if (slugs.isEmpty()) {
      return List.of();
    }
    return jdbc.sql("""
        SELECT id, title, itad_id::text AS itad_id, cover_url, is_dlc, instant_gaming_url, last_manual_refresh_at
        FROM games
        WHERE slug IN (:slugs)
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("games"), JogosBloqueados.filtroSql("games")))
        .param("slugs", slugs)
        .query((rs, linha) -> new JogoParaAtualizar(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("itad_id"),
            rs.getString("cover_url"),
            rs.getObject("is_dlc", Boolean.class),
            rs.getString("instant_gaming_url"),
            rs.getTimestamp("last_manual_refresh_at") == null ? null : rs.getTimestamp("last_manual_refresh_at").toInstant()))
        .list();
  }

  /**
   * Carimba {@code games.last_manual_refresh_at = now()}, iniciando o cooldown do botao
   * "Atualizar precos".
   *
   * <p>O endpoint de refresh e <b>publico</b> (sem login), entao este cooldown e o unico freio
   * contra alguem martelando o botao no mesmo jogo. Vale por jogo, nao por usuario ou IP.
   */
  public void marcarRefreshManual(long jogoId) {
    jdbc.sql("UPDATE games SET last_manual_refresh_at = now() WHERE id = :jogoId")
        .param("jogoId", jogoId)
        .update();
  }

  /**
   * Preenche apenas o que ainda esta faltando — <b>nunca sobrescreve</b> {@code cover_url} ou
   * {@code steam_app_id} ja resolvidos.
   *
   * <p>E a versao usada pelo job em lote, que roda o tempo todo: passar por um jogo de novo nao
   * pode trocar dado que ja esta correto. Para forcar correcao, use
   * {@link #forcarMetadadosSteam}.
   */
  public void atualizarMetadadosSteam(long jogoId, Boolean ehDlc, String capaSteam, Integer steamAppId) {
    jdbc.sql("""
        UPDATE games
        SET is_dlc = COALESCE(:ehDlc, is_dlc),
            cover_url = COALESCE(cover_url, :capaSteam),
            steam_app_id = COALESCE(steam_app_id, :steamAppId),
            last_steam_sync_at = now()
        WHERE id = :jogoId
        """)
        .param("ehDlc", ehDlc)
        .param("capaSteam", capaSteam)
        .param("steamAppId", steamAppId)
        .param("jogoId", jogoId)
        .update();
  }

  /**
   * Contrario de {@link #atualizarMetadadosSteam}: o valor novo <b>sobrescreve</b> o existente,
   * mantendo o antigo apenas quando a Steam nao devolveu nada nesta chamada.
   *
   * <p>Existe pro botao de admin "Preencher tudo agora", cujo proposito e justamente corrigir na
   * hora uma capa ou {@code steam_app_id} errado. Nao usar em job automatico — ficaria trocando
   * dado correto a cada rodada.
   */
  public void forcarMetadadosSteam(long jogoId, Boolean ehDlc, String capaSteam, Integer steamAppId) {
    jdbc.sql("""
        UPDATE games
        SET is_dlc = COALESCE(:ehDlc, is_dlc),
            cover_url = COALESCE(:capaSteam, cover_url),
            steam_app_id = COALESCE(:steamAppId, steam_app_id),
            last_steam_sync_at = now()
        WHERE id = :jogoId
        """)
        .param("ehDlc", ehDlc)
        .param("capaSteam", capaSteam)
        .param("steamAppId", steamAppId)
        .param("jogoId", jogoId)
        .update();
  }

  // Prioriza jogos rankeados (top 2000) sobre o resto do catalogo: sao um preenchimento
  // unico (nao precisam de re-sync continuo como preco), entao vale esgotar os relevantes primeiro.
  private static final String PRIORIDADE_RANK = "CASE WHEN g.rank IS NOT NULL AND g.rank <= 2000 THEN 0 ELSE 1 END ASC, ";

  public List<JogoSteamPendente> listarPendentesSteam(int limite) {
    return jdbc.sql("""
        SELECT g.id, g.title, o.url
        FROM games g
        JOIN offers o ON o.game_id = g.id AND o.store_name = 'Steam'
        WHERE (g.is_dlc IS NULL OR g.cover_url IS NULL OR g.steam_app_id IS NULL)
          %s
          %s
        ORDER BY %s g.last_steam_sync_at ASC NULLS FIRST, g.id ASC
        LIMIT :limite
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g"), PRIORIDADE_RANK))
        .param("limite", limite)
        .query((rs, linha) -> new JogoSteamPendente(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("url")))
        .list();
  }

  public List<JogoDetalhesPendente> listarPendentesDetalhes(int limite) {
    return jdbc.sql("""
        SELECT g.id, g.steam_app_id
        FROM games g
        LEFT JOIN game_details gd ON gd.game_id = g.id
        WHERE g.steam_app_id IS NOT NULL
          AND (gd.game_id IS NULL OR gd.dlc_steam_app_ids IS NULL OR gd.updated_at < now() - interval '30 days')
          %s
          %s
        ORDER BY %s g.id ASC
        LIMIT :limite
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g"), PRIORIDADE_RANK))
        .param("limite", limite)
        .query((rs, linha) -> new JogoDetalhesPendente(rs.getLong("id"), rs.getInt("steam_app_id")))
        .list();
  }

  public long contarPendentesDetalhes() {
    return jdbc.sql("""
        SELECT COUNT(*)
        FROM games g
        LEFT JOIN game_details gd ON gd.game_id = g.id
        WHERE g.steam_app_id IS NOT NULL
          AND (gd.game_id IS NULL OR gd.dlc_steam_app_ids IS NULL OR gd.updated_at < now() - interval '30 days')
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .query(Long.class)
        .single();
  }

  public List<JogoDetalhesPendente> listarPendentesConquistas(int limite) {
    return jdbc.sql("""
        SELECT g.id, g.steam_app_id
        FROM games g
        WHERE g.steam_app_id IS NOT NULL
          AND g.achievements_checked_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM game_achievements ga WHERE ga.game_id = g.id)
          %s
          %s
        ORDER BY %s g.id ASC
        LIMIT :limite
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g"), PRIORIDADE_RANK))
        .param("limite", limite)
        .query((rs, linha) -> new JogoDetalhesPendente(rs.getLong("id"), rs.getInt("steam_app_id")))
        .list();
  }

  public long contarPendentesConquistas() {
    return jdbc.sql("""
        SELECT COUNT(*)
        FROM games g
        WHERE g.steam_app_id IS NOT NULL
          AND g.achievements_checked_at IS NULL
          AND NOT EXISTS (SELECT 1 FROM game_achievements ga WHERE ga.game_id = g.id)
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .query(Long.class)
        .single();
  }

  /**
   * Carimba {@code games.achievements_checked_at}, marcando que a Steam ja foi consultada — mesmo
   * quando ela devolveu esquema vazio (jogo sem conquista nenhuma).
   *
   * <p>Precisa ser chamado tambem no caso vazio. Sem isso, {@link #listarPendentesConquistas}
   * reconsulta pra sempre os mesmos jogos sem conquista (o {@code NOT EXISTS} segue verdadeiro),
   * e como a fila e {@code ORDER BY id ASC} eles entopem o inicio dela e travam o backlog inteiro.
   * Foi exatamente o bug corrigido em 09/08/2026.
   */
  public void marcarConquistasVerificadas(long jogoId) {
    jdbc.sql("UPDATE games SET achievements_checked_at = now() WHERE id = :jogoId")
        .param("jogoId", jogoId)
        .update();
  }

  // Versoes "de um jogo so" das buscas usadas pelo preenchimento em lote (listarPendentesSteam
  // etc.) - usadas pelo botao de admin "Preencher tudo agora" pra atualizar um jogo especifico na
  // hora, sem esperar ele chegar na fila. Ao contrario das versoes em lote, nao filtram por "ainda
  // nao preenchido" - forcam a busca de novo mesmo se o jogo ja tiver dado, pra sempre trazer o
  // mais atual quando o admin pede explicitamente.
  public Optional<JogoSteamPendente> buscarJogoParaMetadadosSteam(long jogoId) {
    return jdbc.sql("""
        SELECT g.id, g.title, o.url
        FROM games g
        JOIN offers o ON o.game_id = g.id AND o.store_name = 'Steam'
        WHERE g.id = :jogoId
        LIMIT 1
        """)
        .param("jogoId", jogoId)
        .query((rs, linha) -> new JogoSteamPendente(rs.getLong("id"), rs.getString("title"), rs.getString("url")))
        .optional();
  }

  public Optional<Integer> buscarSteamAppId(long jogoId) {
    return jdbc.sql("SELECT steam_app_id FROM games WHERE id = :jogoId")
        .param("jogoId", jogoId)
        .query(Integer.class)
        .optional();
  }

  public void salvarDetalhesJogo(DetalhesParaSalvar dados) {
    String destaquesJson;
    try {
      destaquesJson = dados.destaques() == null ? null : objectMapper.writeValueAsString(dados.destaques());
    } catch (com.fasterxml.jackson.core.JsonProcessingException erro) {
      destaquesJson = null;
    }
    jdbc.sql("""
        INSERT INTO game_details (
          game_id, short_description, genres, developers, publishers, release_date, screenshots,
          review_score_desc, review_positive, review_negative,
          trailer_url, trailer_thumbnail, about_full, feature_highlights, categories,
          requirements_min, requirements_rec, dlc_steam_app_ids, updated_at)
        VALUES (:jogoId, :descricao, :generos, :desenvolvedores, :publicadoras, :dataLancamento, :screenshots,
          :notaReviews, :reviewsPositivas, :reviewsNegativas,
          :trailerUrl, :trailerThumbnail, :sobreCompleto, CAST(:destaques AS jsonb), :categorias,
          :requisitosMinimos, :requisitosRecomendados, :dlcAppIds, now())
        ON CONFLICT (game_id) DO UPDATE
          SET short_description = EXCLUDED.short_description,
              genres = EXCLUDED.genres,
              developers = EXCLUDED.developers,
              publishers = EXCLUDED.publishers,
              release_date = EXCLUDED.release_date,
              screenshots = EXCLUDED.screenshots,
              review_score_desc = EXCLUDED.review_score_desc,
              review_positive = EXCLUDED.review_positive,
              review_negative = EXCLUDED.review_negative,
              trailer_url = EXCLUDED.trailer_url,
              trailer_thumbnail = EXCLUDED.trailer_thumbnail,
              about_full = EXCLUDED.about_full,
              feature_highlights = EXCLUDED.feature_highlights,
              categories = EXCLUDED.categories,
              requirements_min = EXCLUDED.requirements_min,
              requirements_rec = EXCLUDED.requirements_rec,
              dlc_steam_app_ids = EXCLUDED.dlc_steam_app_ids,
              updated_at = now()
        """)
        .param("jogoId", dados.jogoId())
        .param("descricao", dados.descricaoCurta())
        .param("generos", dados.generos() == null ? null : dados.generos().toArray(new String[0]))
        .param("desenvolvedores", dados.desenvolvedores() == null ? null : dados.desenvolvedores().toArray(new String[0]))
        .param("publicadoras", dados.publicadoras() == null ? null : dados.publicadoras().toArray(new String[0]))
        .param("dataLancamento", dados.dataLancamento())
        .param("screenshots", dados.screenshots() == null ? null : dados.screenshots().toArray(new String[0]))
        .param("notaReviews", dados.notaReviews())
        .param("reviewsPositivas", dados.reviewsPositivas())
        .param("reviewsNegativas", dados.reviewsNegativas())
        .param("trailerUrl", dados.trailerUrl())
        .param("trailerThumbnail", dados.trailerThumbnail())
        .param("sobreCompleto", dados.sobreCompleto())
        .param("destaques", destaquesJson)
        .param("categorias", dados.categorias() == null ? null : dados.categorias().toArray(new String[0]))
        .param("requisitosMinimos", dados.requisitosMinimos())
        .param("requisitosRecomendados", dados.requisitosRecomendados())
        .param("dlcAppIds", dados.dlcAppIds() == null ? null : dados.dlcAppIds().toArray(new Integer[0]))
        .update();
  }

  @Transactional
  public void salvarConquistas(long jogoId, List<ConquistaParaSalvar> conquistas) {
    if (conquistas.isEmpty()) {
      return;
    }
    for (int i = 0; i < conquistas.size(); i++) {
      ConquistaParaSalvar conquista = conquistas.get(i);
      jdbc.sql("""
          INSERT INTO game_achievements (
            game_id, api_name, display_name, description, icon_url, icon_gray_url, global_percent, position)
          VALUES (:jogoId, :apiName, :displayName, :descricao, :iconeUrl, :iconeCinzaUrl, :percentualGlobal, :posicao)
          ON CONFLICT (game_id, api_name) DO UPDATE
            SET display_name = EXCLUDED.display_name,
                description = EXCLUDED.description,
                icon_url = EXCLUDED.icon_url,
                icon_gray_url = EXCLUDED.icon_gray_url,
                global_percent = EXCLUDED.global_percent,
                position = EXCLUDED.position
          """)
          .param("jogoId", jogoId)
          .param("apiName", conquista.apiName())
          .param("displayName", conquista.displayName())
          .param("descricao", conquista.descricao())
          .param("iconeUrl", conquista.iconeUrl())
          .param("iconeCinzaUrl", conquista.iconeCinzaUrl())
          .param("percentualGlobal", conquista.percentualGlobal())
          .param("posicao", i)
          .update();
    }
  }

  public List<ConquistaJogo> listarConquistas(long jogoId) {
    return jdbc.sql("""
        SELECT api_name, display_name, description, icon_url, global_percent
        FROM game_achievements
        WHERE game_id = :jogoId
        ORDER BY position ASC
        """)
        .param("jogoId", jogoId)
        .query((rs, linha) -> {
          BigDecimal percentual = rs.getBigDecimal("global_percent");
          return new ConquistaJogo(
              rs.getString("api_name"),
              rs.getString("display_name"),
              rs.getString("description"),
              rs.getString("icon_url"),
              percentual == null ? null : percentual.doubleValue());
        })
        .list();
  }

  public Optional<JogoESteamAppId> buscarIdESteamAppIdPorSlug(String slug) {
    return jdbc.sql("SELECT id, steam_app_id FROM games WHERE slug = :slug "
        + ConteudosNaoJogos.filtroSql("games")
        + JogosBloqueados.filtroSql("games"))
        .param("slug", slug)
        .query((rs, linha) -> new JogoESteamAppId(rs.getLong("id"), rs.getObject("steam_app_id", Integer.class)))
        .optional();
  }

  public Optional<DetalhesJogo> buscarDetalhesJogo(long jogoId) {
    return jdbc.sql("""
        SELECT short_description, genres, developers, publishers, release_date, screenshots,
               review_score_desc, review_positive, review_negative,
               trailer_url, trailer_thumbnail, about_full, feature_highlights, categories,
               requirements_min, requirements_rec
        FROM game_details
        WHERE game_id = :jogoId
        """)
        .param("jogoId", jogoId)
        .query((rs, linha) -> new DetalhesJogo(
            rs.getString("short_description"),
            listaDeArray(rs.getArray("genres")),
            listaDeArray(rs.getArray("developers")),
            listaDeArray(rs.getArray("publishers")),
            rs.getString("release_date"),
            listaDeArray(rs.getArray("screenshots")),
            rs.getString("review_score_desc"),
            rs.getObject("review_positive", Integer.class),
            rs.getObject("review_negative", Integer.class),
            rs.getString("trailer_url"),
            rs.getString("trailer_thumbnail"),
            rs.getString("about_full"),
            listaDeDestaques(rs.getString("feature_highlights")),
            listaDeArray(rs.getArray("categories")),
            rs.getString("requirements_min"),
            rs.getString("requirements_rec")))
        .optional();
  }

  private List<DetalhesJogo.DestaqueJogo> listaDeDestaques(String json) {
    if (json == null || json.isBlank()) return List.of();
    try {
      return objectMapper.readValue(json, new TypeReference<List<DetalhesJogo.DestaqueJogo>>() {});
    } catch (com.fasterxml.jackson.core.JsonProcessingException erro) {
      return List.of();
    }
  }

  private static List<String> listaDeArray(java.sql.Array array) throws SQLException {
    if (array == null) return List.of();
    Object[] valores = (Object[]) array.getArray();
    List<String> resultado = new java.util.ArrayList<>();
    for (Object valor : valores) {
      if (valor != null) resultado.add(valor.toString());
    }
    return resultado;
  }

  private List<OfertaJogo> listarOfertas(long jogoId) {
    return jdbc.sql("""
        SELECT o.store_name, o.price, o.regular_price, o.currency, o.url, o.voucher_code
        FROM offers o
        WHERE o.game_id = :jogoId
          %s
        ORDER BY price ASC
        """.formatted(filtroLojaBloqueada("o")))
        .param("jogoId", jogoId)
        .query((rs, linha) -> new OfertaJogo(
            rs.getString("store_name"),
            rs.getBigDecimal("price"),
            rs.getBigDecimal("regular_price"),
            rs.getString("currency"),
            rs.getString("url"),
            rs.getString("voucher_code")))
        .list();
  }

  private static ResumoJogo mapearResumo(ResultSet rs, int linha) throws SQLException {
    return new ResumoJogo(
        rs.getString("slug"),
        rs.getString("title"),
        rs.getString("cover_url"),
        rs.getObject("is_dlc", Boolean.class),
        rs.getBigDecimal("min_price"),
        rs.getBigDecimal("regular_price"),
        rs.getString("store_name"),
        rs.getString("url"));
  }

  private static String filtroPlataforma(String plataforma) {
    String condicao = condicaoPlataforma("op", plataforma);
    if (condicao.isBlank()) return "";
    return "AND EXISTS (SELECT 1 FROM offers op WHERE op.game_id = g.id " + filtroLojaBloqueada("op") + " AND " + condicao + ")";
  }

  private static String filtroLojaBloqueada(String alias) {
    return LojasBloqueadas.filtroSql(alias);
  }

  private static boolean lojaBloqueada(String loja) {
    return LojasBloqueadas.contem(loja);
  }

  private static String filtroOfertaPlataforma(String plataforma) {
    String condicao = condicaoPlataforma("o", plataforma);
    return condicao.isBlank() ? "" : "AND " + condicao;
  }

  // "Lojas preferidas" (preferencias do usuario, ver LojasCatalogo): filtra tanto quais jogos entram
  // no resultado (EXISTS) quanto quais ofertas contam pro MIN(price)/store_name exibidos, senao um
  // jogo so em loja nao-preferida apareceria com preco de uma loja que o usuario nao quer ver.
  private static String filtroLojasPreferidas(String regexLojas) {
    if (regexLojas == null) return "";
    return "AND EXISTS (SELECT 1 FROM offers ol WHERE ol.game_id = g.id " + filtroLojaBloqueada("ol")
        + " AND ol.store_name ~* :lojasRegex)";
  }

  private static String filtroOfertaLojasPreferidas(String regexLojas) {
    return regexLojas == null ? "" : "AND o.store_name ~* :lojasRegex";
  }

  private static String condicaoPlataforma(String alias, String plataforma) {
    String origem = "lower(coalesce(" + alias + ".store_name, '') || ' ' || coalesce(" + alias + ".url, ''))";
    String condicao = switch (plataforma) {
      case "xbox" -> origem + " ~ 'xbox|microsoft'";
      case "playstation" -> origem + " ~ 'playstation|\\mpsn\\M|store\\.playstation\\.com'";
      case "pc" -> "(" + origem + " !~ 'xbox|playstation|\\mpsn\\M|store\\.playstation\\.com')";
      default -> "";
    };
    return condicao;
  }

  /**
   * Monta a clausula {@code HAVING} dos filtros de preco/desconto.
   *
   * <p>E {@code HAVING} e nao {@code WHERE} porque as tres condicoes olham o agregado das ofertas
   * ({@code MIN(price)}, {@code MAX(regular_price)}), que so existe depois do {@code GROUP BY}.
   * Consequencia pratica: com qualquer um desses filtros ativo nao da pra escolher a pagina
   * olhando so {@code games} — a linha precisa ser agregada antes de se saber se entra.
   *
   * @return string vazia quando nenhum filtro foi informado (nao filtrar)
   */
  private static String filtroPreco(Double precoMinimo, Double precoMaximo, Double descontoMinimo) {
    List<String> condicoes = new java.util.ArrayList<>();
    if (precoMinimo != null) condicoes.add("MIN(o.price) >= :precoMinimo");
    if (precoMaximo != null) condicoes.add("MIN(o.price) <= :precoMaximo");
    if (descontoMinimo != null) {
      condicoes.add("ROUND((1 - MIN(o.price) / NULLIF(MAX(o.regular_price), 0)) * 100) >= :descontoMinimo");
    }
    return condicoes.isEmpty() ? "" : "HAVING " + String.join(" AND ", condicoes);
  }

  /**
   * Traduz a ordenacao pedida em {@code ORDER BY}.
   *
   * <p>Quais dependem do agregado das ofertas (e portanto impedem escolher a pagina so por
   * {@code games}):
   *
   * <table border="1">
   *   <caption>Dependencia de agregado por ordenacao</caption>
   *   <tr><th>ordenacao</th><th>depende de MIN/MAX das ofertas?</th></tr>
   *   <tr><td>{@code popularity}</td><td>nao — so {@code g.rank}, {@code g.id}</td></tr>
   *   <tr><td>{@code rank} (padrao)</td><td>parcialmente — ver abaixo</td></tr>
   *   <tr><td>{@code discount}</td><td>sim</td></tr>
   *   <tr><td>{@code price_asc} / {@code price_desc}</td><td>sim</td></tr>
   * </table>
   *
   * <p>A ordenacao padrao promove pro topo os jogos com {@code rank <= 200} que estejam com
   * desconto real (menor preco abaixo de 99% do preco normal — a folga de 1% evita tratar ruido de
   * conversao cambial como promocao), ordenados por maior desconto; o resto sai por
   * {@code rank}. Ou seja: ela precisa do agregado <b>apenas</b> para decidir a fatia de ate 200
   * jogos do topo — da metade pra baixo e ordenacao pura de {@code games}.
   */
  private static String ordenarPor(String ordenacao) {
    return switch (ordenacao) {
      case "discount" -> "ROUND((1 - MIN(o.price) / NULLIF(MAX(o.regular_price), 0)) * 100) DESC NULLS LAST, g.rank ASC NULLS LAST";
      case "price_asc" -> "MIN(o.price) ASC NULLS LAST";
      case "price_desc" -> "MIN(o.price) DESC NULLS LAST";
      case "popularity" -> "g.rank ASC NULLS LAST, g.id ASC";
      default -> """
          CASE
            WHEN g.rank <= 200
              AND MIN(o.price) IS NOT NULL
              AND MAX(o.regular_price) IS NOT NULL
              AND MIN(o.price) < MAX(o.regular_price) * 0.99
            THEN 0 ELSE 1
          END ASC,
          CASE
            WHEN g.rank <= 200
              AND MIN(o.price) IS NOT NULL
              AND MAX(o.regular_price) IS NOT NULL
              AND MIN(o.price) < MAX(o.regular_price) * 0.99
            THEN ROUND((1 - MIN(o.price) / NULLIF(MAX(o.regular_price), 0)) * 100)
            ELSE NULL
          END DESC NULLS LAST,
          g.rank ASC NULLS LAST,
          g.id ASC
          """;
    };
  }

  private record LinhaJogo(Long id, String slug, String title, String coverUrl) {}
  record JogoParaSalvar(String itadId, String title, String slug, String coverUrl, Integer rank) {}
  record IdJogoItad(long id, String itadId) {}
  public record JogoParaSincronizar(long id, String itadId) {}
  private record PrecoMinimo(long jogoId, BigDecimal preco) {}
  public record ResumoFilaColeta(long nuncaSincronizados, String sincronizacaoMaisAntiga, long pendentesSteam) {}
}
