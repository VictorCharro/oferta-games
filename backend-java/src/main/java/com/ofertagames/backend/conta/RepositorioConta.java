package com.ofertagames.backend.conta;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exclusao definitiva da conta (LGPD, art. 18 — issue #17).
 *
 * <p>Apagar {@code auth.users} leva junto, por {@code ON DELETE CASCADE}, perfil, blocos, favoritos,
 * colecoes, avaliacoes, votos, notificacoes e atividades. <b>Mas nao tudo</b>: as tabelas abaixo nao
 * tem FK com {@code auth.users} (conferido no banco em 13/09/2026) e ficariam orfas, com SteamID,
 * biblioteca e horas jogadas de quem pediu pra sair. Por isso sao apagadas explicitamente, na mesma
 * transacao — se qualquer DELETE falhar, nada e apagado.
 *
 * <p>Arquivos do Storage (avatar, banner, imagens de bloco) nao estao no banco: quem os remove e o
 * frontend, com a propria sessao, antes de chamar a exclusao (a policy do bucket so deixa o dono
 * apagar a propria pasta).
 *
 * <p><b>Ao criar tabela nova com dado de usuario</b>: ou ela ganha FK com cascade pra
 * {@code auth.users}, ou entra nesta lista.
 */
@Repository
class RepositorioConta {
  /**
   * Ordem importa pouco (nao ha FK entre elas e auth.users), mas steam_connections vem antes de
   * auth.users por clareza: o cascade dela leva steam_library_games, steam_game_achievements,
   * steam_user_achievements e, por tabela, profile_steam_favorites e profile_collection_items.
   */
  static final List<String> TABELAS_SEM_CASCADE = List.of(
      "steam_auth_states",
      "steam_connections",
      "xbox_library_games",
      "xbox_connections",
      "profile_platinum_order");

  private final JdbcClient jdbc;

  RepositorioConta(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional
  void excluir(String usuarioId) {
    for (String tabela : TABELAS_SEM_CASCADE) {
      // Nome da tabela vem da lista fixa acima, nunca de request.
      jdbc.sql("DELETE FROM public." + tabela + " WHERE user_id = CAST(:usuario AS uuid)")
          .param("usuario", usuarioId)
          .update();
    }
    jdbc.sql("DELETE FROM auth.users WHERE id = CAST(:usuario AS uuid)")
        .param("usuario", usuarioId)
        .update();
  }
}
