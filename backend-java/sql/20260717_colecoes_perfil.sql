-- Colecoes ("Minhas Listas") do perfil: grupos nomeados de jogos.
-- Diferente de favoritos: aceita jogos do catalogo que o usuario nao possui.
-- Um jogo pode estar em varias colecoes (N:N).
CREATE TABLE IF NOT EXISTS profile_collections (
  id bigserial PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  name text NOT NULL,
  position integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_profile_collections_user
ON profile_collections (user_id, position);

-- Cada item e do catalogo (game_id) OU da biblioteca Steam (app_id), nunca os dois.
-- A FK composta (user_id, app_id) usa MATCH SIMPLE: com app_id NULL a checagem e pulada.
CREATE TABLE IF NOT EXISTS profile_collection_items (
  id bigserial PRIMARY KEY,
  collection_id bigint NOT NULL REFERENCES profile_collections(id) ON DELETE CASCADE,
  user_id uuid NOT NULL,
  game_id bigint NULL REFERENCES games(id) ON DELETE CASCADE,
  app_id integer NULL,
  position integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT chk_collection_item CHECK ((game_id IS NOT NULL) <> (app_id IS NOT NULL)),
  FOREIGN KEY (user_id, app_id) REFERENCES steam_library_games(user_id, app_id) ON DELETE CASCADE
);

-- Indices parciais: NULL nao colide, entao cada tipo tem o seu.
CREATE UNIQUE INDEX IF NOT EXISTS ux_collection_game
ON profile_collection_items (collection_id, game_id) WHERE game_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_collection_app
ON profile_collection_items (collection_id, app_id) WHERE app_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_profile_collection_items_colecao
ON profile_collection_items (collection_id, position);

ALTER TABLE profiles ADD COLUMN IF NOT EXISTS show_collections boolean NOT NULL DEFAULT true;
