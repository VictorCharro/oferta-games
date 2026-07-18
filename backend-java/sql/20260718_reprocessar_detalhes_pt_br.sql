-- Mantem os detalhes atuais visiveis enquanto a fila os substitui pela versao pt-BR.
-- A consulta de pendencias processa primeiro os jogos mais relevantes.
UPDATE game_details
SET updated_at = TIMESTAMPTZ '2000-01-01 00:00:00+00';
