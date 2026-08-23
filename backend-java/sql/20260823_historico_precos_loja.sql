-- Nome da loja que tinha o menor preco em cada ponto do historico (price_history.price).
-- Linhas gravadas antes dessa migracao ficam com store_name NULL - nao ha como saber
-- retroativamente qual loja tinha aquele preco.
ALTER TABLE price_history ADD COLUMN IF NOT EXISTS store_name text;
