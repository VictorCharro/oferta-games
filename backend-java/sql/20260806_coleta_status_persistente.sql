CREATE TABLE IF NOT EXISTS coleta_status (
  tipo text PRIMARY KEY,
  em_execucao boolean NOT NULL DEFAULT false,
  inicio_atual timestamptz,
  ultima_conclusao timestamptz,
  ultima_duracao_ms bigint,
  jogos_atualizados integer NOT NULL DEFAULT 0,
  ofertas_atualizadas integer NOT NULL DEFAULT 0,
  ultimo_erro text
);
