ALTER TABLE profile_collections ADD COLUMN IF NOT EXISTS origem text NOT NULL DEFAULT 'usuario';
ALTER TABLE profile_collections ADD CONSTRAINT profile_collections_origem_check CHECK (origem IN ('usuario', 'steam_wishlist'));
CREATE UNIQUE INDEX IF NOT EXISTS profile_collections_wishlist_unica ON profile_collections (user_id) WHERE origem = 'steam_wishlist';
