package com.ofertagames.backend.jogos;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ofertagames.backend.comum.ClassificadorDlc;
import com.ofertagames.backend.comum.ConfiguracaoCache;
import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import com.ofertagames.backend.comum.LojasBloqueadas;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

  // TTL definido em ConfiguracaoCache (10min): evita repetir a query pesada a cada abertura do catalogo.
  @Cacheable(ConfiguracaoCache.CACHE_CATALOGO)
  public List<ResumoJogo> listar(int pagina, int tamanho, String ordenacao, String tipo, String plataforma, Double precoMinimo, Double precoMaximo, Double descontoMinimo, String busca) {
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
        LEFT JOIN offers o ON o.game_id = g.id %s %s
        WHERE 1=1
        %s
        %s
        %s
        %s
        %s
        GROUP BY g.id, g.slug, g.title, g.cover_url, g.is_dlc
        %s
        ORDER BY %s
        LIMIT :tamanho OFFSET :deslocamento
        """.formatted(filtroOfertaPlataforma, filtroLojaBloqueada, filtroTipo, filtroBusca, filtroPlataforma, filtroConteudoNaoJogo, filtroJogoBloqueado, filtroPreco, ordenarPor(ordenacao));

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

  // As DLCs de um jogo vem do array "dlc" da appdetails da Steam (ver ServicoSteam), salvo em
  // game_details.dlc_steam_app_ids quando preenchemos os detalhes do jogo base. So aparecem aqui
  // as que ja existem no nosso catalogo (i.e., ja foram descobertas via ITAD em algum momento).
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

  // Caminho inverso de listarDlcsDoJogo: se este jogo e uma DLC, mostra de qual(is) jogo(s) base
  // ela faz parte, pra dar ida e volta entre as duas paginas. Cruza o proprio steam_app_id do jogo
  // com o dlc_steam_app_ids de todo mundo — so acha algo quando o jogo base ja foi detalhado.
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
    return Arrays.stream(updateCounts)
        .flatMapToInt(Arrays::stream)
        .map(contagem -> contagem == Statement.SUCCESS_NO_INFO ? 1 : Math.max(contagem, 0))
        .sum();
  }

  @Transactional
  public int substituirOfertasItad(long jogoId, List<OfertaParaSalvar> ofertas) {
    jdbc.sql("DELETE FROM offers WHERE game_id = :jogoId AND source = 'itad'")
        .param("jogoId", jogoId)
        .update();
    return salvarOfertas(ofertas);
  }

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

  // Cooldown do refresh manual ("Atualizar precos"): o endpoint e publico (sem login), entao isso
  // e o unico freio contra alguem martelando o botao pro mesmo jogo repetidas vezes.
  public void marcarRefreshManual(long jogoId) {
    jdbc.sql("UPDATE games SET last_manual_refresh_at = now() WHERE id = :jogoId")
        .param("jogoId", jogoId)
        .update();
  }

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

  // Marca que ja verificamos as conquistas deste jogo na Steam, mesmo quando ele nao tem nenhuma
  // (esquema vazio): sem isso, listarPendentesConquistas reconsulta pra sempre os mesmos jogos sem
  // conquistas de verdade (NOT EXISTS continua verdadeiro), entupindo o inicio da fila (ORDER BY
  // id ASC) e impedindo o resto do backlog de avancar.
  public void marcarConquistasVerificadas(long jogoId) {
    jdbc.sql("UPDATE games SET achievements_checked_at = now() WHERE id = :jogoId")
        .param("jogoId", jogoId)
        .update();
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

  private static String filtroPreco(Double precoMinimo, Double precoMaximo, Double descontoMinimo) {
    List<String> condicoes = new java.util.ArrayList<>();
    if (precoMinimo != null) condicoes.add("MIN(o.price) >= :precoMinimo");
    if (precoMaximo != null) condicoes.add("MIN(o.price) <= :precoMaximo");
    if (descontoMinimo != null) {
      condicoes.add("ROUND((1 - MIN(o.price) / NULLIF(MAX(o.regular_price), 0)) * 100) >= :descontoMinimo");
    }
    return condicoes.isEmpty() ? "" : "HAVING " + String.join(" AND ", condicoes);
  }

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
