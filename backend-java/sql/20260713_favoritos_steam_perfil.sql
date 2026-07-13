-- Favoritos pessoais de jogos que pertencem a biblioteca Steam, inclusive itens ausentes no catalogo.
CREATE TABLE IF NOT EXISTS profile_steam_favorites (
  user_id uuid NOT NULL,
  app_id integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, app_id),
  FOREIGN KEY (user_id, app_id)
    REFERENCES steam_library_games(user_id, app_id)
    ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_profile_steam_favorites_user_created
ON profile_steam_favorites (user_id, created_at DESC);
