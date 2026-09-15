-- Rastreamento de erros proprio (issue #29), no lugar do Sentry enquanto nao ha conta: excecoes
-- no navegador dos usuarios e erros 500 do backend, agrupados por assinatura (mesmo erro = uma
-- linha com contador), lidos na aba "Erros" do admin.
--
-- Sem policy de RLS: so o backend le e escreve.

create table if not exists public.app_errors (
  id bigserial primary key,
  origin text not null check (origin in ('navegador', 'servidor')),
  -- sha256 de origem + mensagem + primeira linha util da pilha: agrupa o mesmo erro vindo de
  -- usuarios diferentes.
  signature text not null unique,
  message text not null check (char_length(message) between 1 and 500),
  detail text check (detail is null or char_length(detail) <= 4000),
  page_path text check (page_path is null or char_length(page_path) <= 300),
  user_agent text check (user_agent is null or char_length(user_agent) <= 300),
  occurrences integer not null default 1,
  first_seen_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now(),
  -- Marcado como resolvido no admin; se o erro voltar a acontecer, reabre sozinho.
  resolved_at timestamptz
);

create index if not exists app_errors_abertos_idx on public.app_errors (last_seen_at desc) where resolved_at is null;

alter table public.app_errors enable row level security;
