-- Favoritos pessoais do perfil. Nao confundir com favorites, usado para monitoramento de preco.
CREATE TABLE IF NOT EXISTS profile_favorites (
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  game_id bigint NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, game_id)
);

CREATE INDEX IF NOT EXISTS idx_profile_favorites_user_created
ON profile_favorites (user_id, created_at DESC);
