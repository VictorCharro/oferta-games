-- RepositorioJogos.listarJogosBaseDaDlc (o caminho "esta DLC pertence a qual jogo base?") cruza o
-- steam_app_id do jogo contra o array dlc_steam_app_ids de TODOS os game_details. A tabela so tinha
-- a PK, entao isso virava Seq Scan em 38k linhas -- ~610ms a cada abertura de pagina de jogo, que
-- era a maior parte dos 2,45s do GET /api/games/{slug}.
--
-- Com o indice: 0,17ms (buffers 8.085 -> 3).
CREATE INDEX IF NOT EXISTS idx_game_details_dlc_steam_app_ids
  ON game_details USING GIN (dlc_steam_app_ids);
