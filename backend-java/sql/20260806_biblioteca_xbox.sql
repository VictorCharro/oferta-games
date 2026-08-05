-- Biblioteca Xbox (titleHistory da OpenXBL). So UPSERT, nunca DELETE: diferente da Steam
-- (que substitui a biblioteca inteira a cada sync), aqui a sincronizacao so incrementa/atualiza,
-- pra nunca apagar nada que o perfil do usuario ja tenha.
CREATE TABLE IF NOT EXISTS xbox_library_games (
  user_id uuid NOT NULL,
  title_id text NOT NULL,
  title text,
  cover_url text,
  achievements_unlocked integer NOT NULL DEFAULT 0,
  achievements_total integer NOT NULL DEFAULT 0,
  gamerscore_unlocked integer NOT NULL DEFAULT 0,
  gamerscore_total integer NOT NULL DEFAULT 0,
  last_played_at timestamptz,
  last_synced_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, title_id)
);
