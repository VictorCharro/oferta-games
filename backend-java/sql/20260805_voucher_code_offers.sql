-- Codigo do cupom ITAD quando o menor preco de uma oferta ja vem com desconto de cupom aplicado
-- (buscamos com vouchers=true na API da ITAD, que ja calcula o preco final com o cupom).
ALTER TABLE offers ADD COLUMN IF NOT EXISTS voucher_code text NULL;
