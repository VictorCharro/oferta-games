package com.ofertagames.backend.perfis;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Denuncias de perfil (tabela profile_reports, ver sql/20260913_moderacao_perfis.sql). */
@Repository
class RepositorioModeracao {
  private final JdbcClient jdbc;

  RepositorioModeracao(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void registrar(String denunciadoId, String denuncianteId, String motivo) {
    jdbc.sql("""
        INSERT INTO profile_reports (reported_user_id, reporter_user_id, reason)
        VALUES (CAST(:denunciado AS uuid), CAST(:denunciante AS uuid), :motivo)
        """)
        .param("denunciado", denunciadoId)
        .param("denunciante", denuncianteId)
        .param("motivo", motivo)
        .update();
  }

  long contarDoDenuncianteNasUltimas24h(String denuncianteId) {
    return jdbc.sql("SELECT count(*) FROM profile_reports WHERE reporter_user_id = CAST(:id AS uuid) AND created_at > now() - interval '24 hours'")
        .param("id", denuncianteId)
        .query(Long.class)
        .single();
  }

  /** Mesma pessoa denunciando o mesmo perfil de novo sem resolucao: nao duplica a fila. */
  boolean jaDenunciouEmAberto(String denunciadoId, String denuncianteId) {
    return jdbc.sql("""
        SELECT EXISTS (SELECT 1 FROM profile_reports
          WHERE reported_user_id = CAST(:denunciado AS uuid) AND reporter_user_id = CAST(:denunciante AS uuid)
            AND resolved_at IS NULL)
        """)
        .param("denunciado", denunciadoId)
        .param("denunciante", denuncianteId)
        .query(Boolean.class)
        .single();
  }

  List<DenunciaAberta> listarAbertas() {
    return jdbc.sql("""
        SELECT r.id, p.handle, p.display_name, p.blocked_at IS NOT NULL AS bloqueado, r.reason, r.created_at,
               count(*) OVER (PARTITION BY r.reported_user_id) AS total_do_perfil
        FROM profile_reports r
        LEFT JOIN profiles p ON p.user_id = r.reported_user_id
        WHERE r.resolved_at IS NULL
        ORDER BY r.created_at DESC
        LIMIT 200
        """)
        .query((rs, linha) -> new DenunciaAberta(
            rs.getLong("id"),
            rs.getString("handle"),
            rs.getString("display_name"),
            rs.getBoolean("bloqueado"),
            rs.getString("reason"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getLong("total_do_perfil")))
        .list();
  }

  List<PerfilBloqueado> listarBloqueados() {
    return jdbc.sql("""
        SELECT handle, display_name, blocked_at
        FROM profiles
        WHERE blocked_at IS NOT NULL
        ORDER BY blocked_at DESC
        LIMIT 200
        """)
        .query((rs, linha) -> new PerfilBloqueado(
            rs.getString("handle"),
            rs.getString("display_name"),
            rs.getObject("blocked_at", OffsetDateTime.class)))
        .list();
  }

  int resolver(long id) {
    return jdbc.sql("UPDATE profile_reports SET resolved_at = now() WHERE id = :id AND resolved_at IS NULL")
        .param("id", id)
        .update();
  }

  record DenunciaAberta(long id, String handle, String nomeExibicao, boolean perfilBloqueado, String motivo,
      OffsetDateTime criadaEm, long totalDoPerfil) {}

  record PerfilBloqueado(String handle, String nomeExibicao, OffsetDateTime bloqueadoEm) {}
}
