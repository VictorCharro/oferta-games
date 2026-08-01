-- Ordenacao manual dos jogos platinados no bloco de perfil.
-- Tabela dedicada (em vez de uma coluna em steam_library_games) porque essa tabela e
-- inteiramente substituida a cada sincronizacao de biblioteca (delete + insert), o que
-- apagaria qualquer posicao guardada nela.
CREATE TABLE IF NOT EXISTS profile_platinum_order (
  user_id uuid NOT NULL,
  app_id integer NOT NULL,
  position integer NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, app_id)
);
