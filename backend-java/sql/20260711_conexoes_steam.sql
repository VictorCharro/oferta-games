CREATE TABLE IF NOT EXISTS steam_auth_states (
  state uuid PRIMARY KEY,
  user_id uuid NOT NULL,
  expires_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS steam_connections (
  user_id uuid PRIMARY KEY,
  steam_id text NOT NULL UNIQUE,
  persona_name text NULL,
  avatar_url text NULL,
  connected_at timestamptz NOT NULL DEFAULT now(),
  last_library_sync_at timestamptz NULL,
  last_achievement_sync_at timestamptz NULL,
  last_error text NULL
);

CREATE TABLE IF NOT EXISTS steam_library_games (
  user_id uuid NOT NULL,
  app_id integer NOT NULL,
  title text NOT NULL,
  playtime_minutes integer NOT NULL DEFAULT 0,
  icon_hash text NULL,
  last_synced_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, app_id),
  FOREIGN KEY (user_id) REFERENCES steam_connections(user_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_steam_library_games_achievements
  ON steam_library_games (user_id, playtime_minutes DESC, last_synced_at DESC);

CREATE TABLE IF NOT EXISTS steam_game_achievements (
  user_id uuid NOT NULL,
  app_id integer NOT NULL,
  unlocked_count integer NOT NULL DEFAULT 0,
  total_count integer NOT NULL DEFAULT 0,
  last_synced_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, app_id),
  FOREIGN KEY (user_id) REFERENCES steam_connections(user_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_steam_achievements_sync
  ON steam_game_achievements (user_id, last_synced_at ASC);
