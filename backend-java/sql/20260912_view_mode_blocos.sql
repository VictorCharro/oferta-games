-- Modo de visualizacao por bloco: os blocos de jogos (favoritos, platinados, biblioteca,
-- wishlist, mais jogados) podem ser mostrados como cards com capa ou como lista compacta.
-- NULL = padrao do tipo (favoritos nasce em lista; os outros em cards).
ALTER TABLE profile_blocks ADD COLUMN IF NOT EXISTS view_mode text;
