ALTER TABLE games ADD COLUMN IF NOT EXISTS steam_app_id integer NULL;
CREATE INDEX IF NOT EXISTS idx_games_steam_app_id ON games (steam_app_id) WHERE steam_app_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS game_details (
  game_id bigint PRIMARY KEY REFERENCES games(id) ON DELETE CASCADE,
  short_description text,
  genres text[],
  developers text[],
  publishers text[],
  release_date text,
  screenshots text[],
  review_score_desc text,
  review_positive integer,
  review_negative integer,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS game_achievements (
  id bigserial PRIMARY KEY,
  game_id bigint NOT NULL REFERENCES games(id) ON DELETE CASCADE,
  api_name text NOT NULL,
  display_name text,
  description text,
  icon_url text,
  icon_gray_url text,
  global_percent numeric,
  position integer NOT NULL DEFAULT 0,
  UNIQUE (game_id, api_name)
);
