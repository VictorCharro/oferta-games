ALTER TABLE steam_library_games ADD COLUMN IF NOT EXISTS cover_url text NULL;
ALTER TABLE steam_library_games ADD COLUMN IF NOT EXISTS cover_synced_at timestamptz NULL;

CREATE INDEX IF NOT EXISTS idx_steam_library_games_sem_capa
  ON steam_library_games (cover_synced_at ASC NULLS FIRST)
  WHERE cover_url IS NULL;
