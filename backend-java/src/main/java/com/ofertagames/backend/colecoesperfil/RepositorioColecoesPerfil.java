package com.ofertagames.backend.colecoesperfil;

import com.ofertagames.backend.comum.ConteudosNaoJogos;
import com.ofertagames.backend.comum.JogosBloqueados;
import com.ofertagames.backend.favoritosperfil.FavoritoPerfilJogo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RepositorioColecoesPerfil {
  // Proxima posicao livre dentro da colecao: itens novos vao para o fim.
  private static final String SQL_PROXIMA_POSICAO =
      "(SELECT COALESCE(MAX(position), -1) + 1 FROM profile_collection_items WHERE collection_id = :colecaoId)";

  private final JdbcClient jdbc;

  RepositorioColecoesPerfil(JdbcClient jdbc) { this.jdbc = jdbc; }

  public List<ColecaoPerfil> listarPorUsuario(String usuarioId) {
    Map<Long, List<FavoritoPerfilJogo>> jogosPorColecao = agruparItens(usuarioId);
    return jdbc.sql("SELECT id, name FROM profile_collections WHERE user_id = CAST(:usuarioId AS uuid) ORDER BY position ASC, created_at ASC")
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> {
          long id = rs.getLong("id");
          return new ColecaoPerfil(id, rs.getString("name"), jogosPorColecao.getOrDefault(id, List.of()));
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

  // Os JOINs internos ja separam os ramos: game_id nulo nao casa com games, app_id nulo nao casa com a biblioteca.
  private List<ItemColecao> listarItens(String usuarioId) {
    return jdbc.sql("""
        SELECT
          i.collection_id,
          g.slug,
          NULL::integer AS steam_app_id,
          g.title,
          g.cover_url,
          NULL::text AS icon_hash,
          g.is_dlc,
          NULL::integer AS playtime_minutes,
          NULL::integer AS unlocked_count,
          NULL::integer AS total_count,
          oferta.price AS min_price,
          oferta.regular_price AS regular_price,
          i.created_at::text AS added_at,
          i.position AS position
        FROM profile_collection_items i
        JOIN profile_collections c ON c.id = i.collection_id
        JOIN games g ON g.id = i.game_id
        LEFT JOIN LATERAL (
          SELECT price, regular_price
          FROM offers
          WHERE game_id = g.id
          ORDER BY price ASC
          LIMIT 1
        ) oferta ON true
        WHERE c.user_id = CAST(:usuarioId AS uuid)
          %s
          %s
        UNION ALL
        SELECT
          i.collection_id,
          NULL::text AS slug,
          b.app_id AS steam_app_id,
          b.title,
          b.cover_url,
          b.icon_hash,
          NULL::boolean AS is_dlc,
          b.playtime_minutes,
          COALESCE(a.unlocked_count, 0) AS unlocked_count,
          COALESCE(a.total_count, 0) AS total_count,
          NULL::numeric AS min_price,
          NULL::numeric AS regular_price,
          i.created_at::text AS added_at,
          i.position AS position
        FROM profile_collection_items i
        JOIN profile_collections c ON c.id = i.collection_id
        JOIN steam_library_games b ON b.user_id = i.user_id AND b.app_id = i.app_id
        LEFT JOIN steam_game_achievements a ON a.user_id = b.user_id AND a.app_id = b.app_id
        WHERE c.user_id = CAST(:usuarioId AS uuid)
        ORDER BY position ASC, added_at DESC
        """.formatted(ConteudosNaoJogos.filtroSql("g"), JogosBloqueados.filtroSql("g")))
        .param("usuarioId", usuarioId)
        .query((rs, linha) -> new ItemColecao(
            rs.getLong("collection_id"),
            new FavoritoPerfilJogo(
                rs.getString("slug"),
                rs.getObject("steam_app_id", Integer.class),
                rs.getString("title"),
                rs.getString("cover_url"),
                rs.getString("icon_hash"),
                rs.getObject("is_dlc", Boolean.class),
                rs.getObject("playtime_minutes", Integer.class),
                rs.getObject("unlocked_count", Integer.class),
                rs.getObject("total_count", Integer.class),
                rs.getBigDecimal("min_price"),
                rs.getBigDecimal("regular_price"),
                rs.getString("added_at"))))
        .list();
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

  private record ItemColecao(long colecaoId, FavoritoPerfilJogo jogo) {}
}
