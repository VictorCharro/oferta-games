package com.ofertagames.backend.colecoesperfil;

import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import com.ofertagames.backend.favoritosperfil.FavoritoPerfilJogo;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code profile_collections}/{@code profile_collection_items} moram no Supabase (FK com
 * {@code auth.users}); {@code games}/{@code offers} moram no Postgres do catalogo, na VM -
 * {@link #listarItens} busca nos dois bancos e junta em Java.
 */
@Repository
public class RepositorioColecoesPerfil {
  // Proxima posicao livre dentro da colecao: itens novos vao para o fim.
  private static final String SQL_PROXIMA_POSICAO =
      "(SELECT COALESCE(MAX(position), -1) + 1 FROM profile_collection_items WHERE collection_id = :colecaoId)";

  private final JdbcClient jdbc;
  private final JdbcClient jdbcCatalogo;

  RepositorioColecoesPerfil(JdbcClient jdbc, @Qualifier("catalogo") JdbcClient jdbcCatalogo) {
    this.jdbc = jdbc;
    this.jdbcCatalogo = jdbcCatalogo;
  }

  public List<ColecaoPerfil> listarPorUsuario(String usuarioId) {
    Map<Long, List<FavoritoPerfilJogo>> jogosPorColecao = agruparItens(usuarioId);
    return jdbc.sql("SELECT id, name, origem FROM profile_collections WHERE user_id = CAST(:usuarioId AS uuid) ORDER BY position ASC, created_at ASC")
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> {
          long id = rs.getLong("id");
          boolean origemSistema = !"usuario".equals(rs.getString("origem"));
          return new ColecaoPerfil(id, rs.getString("name"), origemSistema, jogosPorColecao.getOrDefault(id, List.of()));
        })
        .list();
  }

  // Uma consulta unica com os itens de todas as colecoes do usuario, agrupada em memoria (evita N+1).
  private Map<Long, List<FavoritoPerfilJogo>> agruparItens(String usuarioId) {
    Map<Long, List<FavoritoPerfilJogo>> agrupado = new LinkedHashMap<>();
    for (ItemColecao item : listarItens(usuarioId)) {
      agrupado.computeIfAbsent(item.colecaoId(), chave -> new ArrayList<>()).add(item.jogo());
    }
    return agrupado;
  }

  // Os dois ramos (jogo do catalogo / jogo da biblioteca Steam) sao independentes: game_id nulo
  // num item nunca casa com o outro ramo. O ramo de jogo do catalogo busca no banco do catalogo
  // (separado desde a migracao das tabelas de jogo pra fora do Supabase) e junta em Java.
  private List<ItemColecao> listarItens(String usuarioId) {
    List<ItemDeJogoBruto> itensDeJogo = jdbc.sql("""
        SELECT i.collection_id, i.game_id, i.created_at::text AS added_at, i.position AS position
        FROM profile_collection_items i
        JOIN profile_collections c ON c.id = i.collection_id
        WHERE c.user_id = CAST(:usuarioId AS uuid) AND i.game_id IS NOT NULL
        """)
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new ItemDeJogoBruto(
            rs.getLong("collection_id"), rs.getLong("game_id"), rs.getString("added_at"), rs.getInt("position")))
        .list();

    Map<Long, DadosJogoCatalogo> dadosPorJogo = buscarDadosCatalogo(
        itensDeJogo.stream().map(ItemDeJogoBruto::gameId).distinct().toList());

    List<ItemComOrdem> itens = new ArrayList<>();
    for (ItemDeJogoBruto i : itensDeJogo) {
      DadosJogoCatalogo d = dadosPorJogo.get(i.gameId());
      // Jogo que sumiu do catalogo (bloqueado, virou conteudo-nao-jogo) nao aparece mais - mesmo
      // comportamento do JOIN antigo.
      if (d == null) continue;
      itens.add(new ItemComOrdem(i.position(), i.addedAt(), i.collectionId(), new FavoritoPerfilJogo(
          d.slug(), null, d.title(), d.coverUrl(), null, d.isDlc(), null, null, null,
          d.minPrice(), d.regularPrice(), i.addedAt())));
    }

    itens.addAll(jdbc.sql("""
        SELECT
          i.collection_id,
          b.app_id AS steam_app_id,
          b.title,
          b.cover_url,
          b.icon_hash,
          b.playtime_minutes,
          COALESCE(a.unlocked_count, 0) AS unlocked_count,
          COALESCE(a.total_count, 0) AS total_count,
          i.created_at::text AS added_at,
          i.position AS position
        FROM profile_collection_items i
        JOIN profile_collections c ON c.id = i.collection_id
        JOIN steam_library_games b ON b.user_id = i.user_id AND b.app_id = i.app_id
        LEFT JOIN steam_game_achievements a ON a.user_id = b.user_id AND a.app_id = b.app_id
        WHERE c.user_id = CAST(:usuarioId AS uuid)
        """)
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> {
          String addedAt = rs.getString("added_at");
          return new ItemComOrdem(rs.getInt("position"), addedAt, rs.getLong("collection_id"), new FavoritoPerfilJogo(
              null,
              rs.getObject("steam_app_id", Integer.class),
              rs.getString("title"),
              rs.getString("cover_url"),
              rs.getString("icon_hash"),
              null,
              rs.getObject("playtime_minutes", Integer.class),
              rs.getObject("unlocked_count", Integer.class),
              rs.getObject("total_count", Integer.class),
              null,
              null,
              addedAt));
        })
        .list());

    return itens.stream()
        .sorted(Comparator.comparingInt(ItemComOrdem::position).thenComparing(ItemComOrdem::addedAt, Comparator.reverseOrder()))
        .map(i -> new ItemColecao(i.collectionId(), i.jogo()))
        .toList();
  }

  private Map<Long, DadosJogoCatalogo> buscarDadosCatalogo(List<Long> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<Long, DadosJogoCatalogo> resultado = new HashMap<>();
    for (DadosJogoCatalogo d : jdbcCatalogo.sql("""
        SELECT
          g.id,
          g.slug,
          g.title,
          g.cover_url,
          g.is_dlc,
          oferta.price AS min_price,
          oferta.regular_price AS regular_price
        FROM games g
        LEFT JOIN LATERAL (
          SELECT price, regular_price
          FROM offers
          WHERE game_id = g.id
          ORDER BY price ASC
          LIMIT 1
        ) oferta ON true
        WHERE g.id IN (:ids)
          %s
          %s
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .param("ids", ids)
        .query((rs, linha) -> new DadosJogoCatalogo(
            rs.getLong("id"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("cover_url"),
            rs.getObject("is_dlc", Boolean.class),
            rs.getBigDecimal("min_price"),
            rs.getBigDecimal("regular_price")))
        .list()) {
      resultado.put(d.id(), d);
    }
    return resultado;
  }

  public long criar(String usuarioId, String nome) {
    return jdbc.sql("""
        INSERT INTO profile_collections (user_id, name, position)
        VALUES (CAST(:usuarioId AS uuid), :nome,
          (SELECT COALESCE(MAX(position), -1) + 1 FROM profile_collections WHERE user_id = CAST(:usuarioId AS uuid)))
        RETURNING id
        """)
        .param("usuarioId", usuarioId)
        .param("nome", nome)
        .query(Long.class)
        .single();
  }

  public boolean renomear(String usuarioId, long colecaoId, String nome) {
    return jdbc.sql("UPDATE profile_collections SET name = :nome WHERE id = :colecaoId AND user_id = CAST(:usuarioId AS uuid)")
        .param("nome", nome)
        .param("colecaoId", colecaoId)
        .param("usuarioId", usuarioId)
        .update() > 0;
  }

  public boolean excluir(String usuarioId, long colecaoId) {
    return jdbc.sql("DELETE FROM profile_collections WHERE id = :colecaoId AND user_id = CAST(:usuarioId AS uuid)")
        .param("colecaoId", colecaoId)
        .param("usuarioId", usuarioId)
        .update() > 0;
  }

  public boolean pertenceAoUsuario(String usuarioId, long colecaoId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM profile_collections WHERE id = :colecaoId AND user_id = CAST(:usuarioId AS uuid))")
        .param("colecaoId", colecaoId)
        .param("usuarioId", usuarioId)
        .query(Boolean.class)
        .single();
  }

  /**
   * {@code empty} quando a colecao nao existe ou nao e do usuario (controller trata os dois casos
   * como 404, pra nao revelar a existencia dela). Quando presente, {@code "usuario"} ou o nome de
   * uma origem de sistema (hoje so {@code "steam_wishlist"}) — o controller bloqueia
   * renomear/excluir/adicionar/remover item quando a origem nao e {@code "usuario"}.
   */
  public Optional<String> origemDaColecao(String usuarioId, long colecaoId) {
    return jdbc.sql("SELECT origem FROM profile_collections WHERE id = :colecaoId AND user_id = CAST(:usuarioId AS uuid)")
        .param("colecaoId", colecaoId)
        .param("usuarioId", usuarioId)
        .query(String.class)
        .optional();
  }

  public int contarColecoes(String usuarioId) {
    return jdbc.sql("SELECT COUNT(*) FROM profile_collections WHERE user_id = CAST(:usuarioId AS uuid)")
        .param("usuarioId", usuarioId)
        .query(Integer.class)
        .single();
  }

  public int contarItens(long colecaoId) {
    return jdbc.sql("SELECT COUNT(*) FROM profile_collection_items WHERE collection_id = :colecaoId")
        .param("colecaoId", colecaoId)
        .query(Integer.class)
        .single();
  }

  public Optional<String> tituloSteam(String usuarioId, int appId) {
    return jdbc.sql("SELECT title FROM steam_library_games WHERE user_id = CAST(:usuarioId AS uuid) AND app_id = :appId")
        .param("usuarioId", usuarioId)
        .param("appId", appId)
        .query(String.class)
        .optional();
  }

  public boolean adicionarJogo(String usuarioId, long colecaoId, long jogoId) {
    return jdbc.sql("""
        INSERT INTO profile_collection_items (collection_id, user_id, game_id, position)
        VALUES (:colecaoId, CAST(:usuarioId AS uuid), :jogoId, %s)
        ON CONFLICT DO NOTHING
        """.formatted(SQL_PROXIMA_POSICAO))
        .param("colecaoId", colecaoId)
        .param("usuarioId", usuarioId)
        .param("jogoId", jogoId)
        .update() > 0;
  }

  public boolean adicionarSteam(String usuarioId, long colecaoId, int appId) {
    return jdbc.sql("""
        INSERT INTO profile_collection_items (collection_id, user_id, app_id, position)
        VALUES (:colecaoId, CAST(:usuarioId AS uuid), :appId, %s)
        ON CONFLICT DO NOTHING
        """.formatted(SQL_PROXIMA_POSICAO))
        .param("colecaoId", colecaoId)
        .param("usuarioId", usuarioId)
        .param("appId", appId)
        .update() > 0;
  }

  public boolean removerJogo(String usuarioId, long colecaoId, long jogoId) {
    return jdbc.sql("DELETE FROM profile_collection_items WHERE collection_id = :colecaoId AND user_id = CAST(:usuarioId AS uuid) AND game_id = :jogoId")
        .param("colecaoId", colecaoId)
        .param("usuarioId", usuarioId)
        .param("jogoId", jogoId)
        .update() > 0;
  }

  public boolean removerSteam(String usuarioId, long colecaoId, int appId) {
    return jdbc.sql("DELETE FROM profile_collection_items WHERE collection_id = :colecaoId AND user_id = CAST(:usuarioId AS uuid) AND app_id = :appId")
        .param("colecaoId", colecaoId)
        .param("usuarioId", usuarioId)
        .param("appId", appId)
        .update() > 0;
  }

  /**
   * Id da colecao de sistema do usuario para a {@code origem} dada (ex: {@code "steam_wishlist"}),
   * criando com o nome padrao se ainda nao existir. So existe uma por usuario por origem
   * (indice unico parcial {@code profile_collections_wishlist_unica}).
   */
  public long buscarOuCriarColecaoSistema(String usuarioId, String origem, String nomePadrao) {
    Optional<Long> existente = jdbc.sql("SELECT id FROM profile_collections WHERE user_id = CAST(:usuarioId AS uuid) AND origem = :origem")
        .param("usuarioId", usuarioId)
        .param("origem", origem)
        .query(Long.class)
        .optional();
    if (existente.isPresent()) {
      return existente.get();
    }
    return jdbc.sql("""
        INSERT INTO profile_collections (user_id, name, origem, position)
        VALUES (CAST(:usuarioId AS uuid), :nome, :origem,
          (SELECT COALESCE(MAX(position), -1) + 1 FROM profile_collections WHERE user_id = CAST(:usuarioId AS uuid)))
        RETURNING id
        """)
        .param("usuarioId", usuarioId)
        .param("nome", nomePadrao)
        .param("origem", origem)
        .query(Long.class)
        .single();
  }

  /**
   * Reconcilia os itens de uma colecao de sistema pra ficarem exatamente iguais a
   * {@code gameIdsNaOrdem} (posicao = indice na lista): insere os que faltam, atualiza a posicao
   * dos que ja existem e remove os que sairam da lista (ex: jogo comprado ou removido da wishlist
   * real). Unico lugar do backend que faz DELETE em {@code profile_collection_items} fora de um
   * pedido explicito do dono — seguro porque e escopado a uma colecao de sistema so, nunca a uma
   * colecao criada manualmente (ver {@link #origemDaColecao}).
   */
  public void sincronizarItensSistema(long colecaoId, String usuarioId, List<Long> gameIdsNaOrdem) {
    // Lista tipicamente pequena (dezenas de itens - wishlist de uma pessoa), entao um loop de
    // upserts individuais e simples e rapido o bastante; nao precisa de batch de verdade aqui.
    for (int posicao = 0; posicao < gameIdsNaOrdem.size(); posicao++) {
      jdbc.sql("""
          INSERT INTO profile_collection_items (collection_id, user_id, game_id, position)
          VALUES (:colecaoId, CAST(:usuarioId AS uuid), :jogoId, :posicao)
          ON CONFLICT (collection_id, game_id) WHERE game_id IS NOT NULL
            DO UPDATE SET position = EXCLUDED.position
          """)
          .param("colecaoId", colecaoId)
          .param("usuarioId", usuarioId)
          .param("jogoId", gameIdsNaOrdem.get(posicao))
          .param("posicao", posicao)
          .update();
    }
    jdbc.sql("""
        DELETE FROM profile_collection_items
        WHERE collection_id = :colecaoId
          AND game_id IS NOT NULL
          AND game_id NOT IN (:gameIds)
        """)
        .param("colecaoId", colecaoId)
        .param("gameIds", gameIdsNaOrdem.isEmpty() ? List.of(-1L) : gameIdsNaOrdem)
        .update();
  }

  private record ItemColecao(long colecaoId, FavoritoPerfilJogo jogo) {}

  private record ItemDeJogoBruto(long collectionId, long gameId, String addedAt, int position) {}

  private record ItemComOrdem(int position, String addedAt, long collectionId, FavoritoPerfilJogo jogo) {}

  private record DadosJogoCatalogo(
      long id, String slug, String title, String coverUrl, Boolean isDlc, BigDecimal minPrice, BigDecimal regularPrice) {}
}
