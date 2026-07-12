ALTER TABLE steam_connections
  ADD COLUMN IF NOT EXISTS last_public_profile_refresh_at timestamptz NULL;
