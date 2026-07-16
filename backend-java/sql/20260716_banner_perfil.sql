-- Banner (capa) do perfil publico, com o mesmo enquadramento persistido do avatar.
ALTER TABLE profiles
  ADD COLUMN IF NOT EXISTS banner_url text,
  ADD COLUMN IF NOT EXISTS banner_zoom numeric(3,2) NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS banner_position_x integer NOT NULL DEFAULT 50,
  ADD COLUMN IF NOT EXISTS banner_position_y integer NOT NULL DEFAULT 50;
