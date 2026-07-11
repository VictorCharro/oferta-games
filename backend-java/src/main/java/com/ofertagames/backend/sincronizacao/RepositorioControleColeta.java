package com.ofertagames.backend.sincronizacao;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RepositorioControleColeta {
  private final JdbcClient jdbc;

  RepositorioControleColeta(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  boolean tentarAdquirir(String nome) {
    return jdbc.sql("""
        INSERT INTO sync_locks (name, locked_until)
        VALUES (:nome, now() + interval '30 minutes')
        ON CONFLICT (name) DO UPDATE
          SET locked_until = EXCLUDED.locked_until
          WHERE sync_locks.locked_until <= now()
        RETURNING name
        """)
        .param("nome", nome)
        .query(String.class)
        .optional()
        .isPresent();
  }

  void liberar(String nome) {
    jdbc.sql("UPDATE sync_locks SET locked_until = now() WHERE name = :nome")
        .param("nome", nome)
        .update();
  }
}
