ALTER TABLE games
  ADD COLUMN IF NOT EXISTS last_price_sync_at timestamptz,
  ADD COLUMN IF NOT EXISTS last_steam_sync_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_games_price_sync_relevant
  ON games (last_price_sync_at ASC NULLS FIRST, rank ASC, id ASC)
  WHERE itad_id IS NOT NULL AND rank IS NOT NULL AND rank <= 2000;

CREATE INDEX IF NOT EXISTS idx_games_price_sync_general
  ON games (last_price_sync_at ASC NULLS FIRST, rank ASC NULLS LAST, id ASC)
  WHERE itad_id IS NOT NULL AND (rank IS NULL OR rank > 2000);

CREATE INDEX IF NOT EXISTS idx_games_steam_sync
  ON games (last_steam_sync_at ASC NULLS FIRST, id ASC)
  WHERE is_dlc IS NULL OR cover_url IS NULL;

CREATE TABLE IF NOT EXISTS sync_locks (
  name text PRIMARY KEY,
  locked_until timestamptz NOT NULL
);
