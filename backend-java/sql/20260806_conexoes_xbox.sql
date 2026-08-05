-- Conexao de conta Xbox via OpenXBL (xbl.io): fluxo OAuth "Xbox App" (nao a API key
-- pessoal), o usuario loga com a conta Microsoft dele e a gente recebe xuid/gamertag/
-- token pra fazer chamadas em nome dele depois.
CREATE TABLE IF NOT EXISTS xbox_connections (
  user_id uuid PRIMARY KEY,
  xuid text NOT NULL,
  gamertag text,
  avatar_url text,
  gamerscore integer,
  access_token text,
  connected_at timestamptz NOT NULL DEFAULT now(),
  last_library_sync_at timestamptz,
  last_error text
);
