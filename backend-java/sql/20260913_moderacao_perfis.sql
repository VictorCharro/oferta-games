-- Moderacao de perfis publicos (issue #25).
--
-- profile_reports: denuncias feitas por usuarios logados. Sem policy de RLS de proposito: so o
-- backend (role postgres) le e escreve; o navegador nunca acessa esta tabela direto.
-- profiles.blocked_at: perfil tirado do ar pelo admin. Diferente de is_public, o dono NAO consegue
-- reverter pelas configuracoes.

alter table public.profiles add column if not exists blocked_at timestamptz;

create table if not exists public.profile_reports (
  id bigserial primary key,
  reported_user_id uuid not null references auth.users(id) on delete cascade,
  -- set null: quem denunciou pode excluir a propria conta sem apagar a denuncia.
  reporter_user_id uuid references auth.users(id) on delete set null,
  reason text not null check (char_length(reason) between 3 and 500),
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);

create index if not exists profile_reports_abertas_idx
  on public.profile_reports (created_at desc) where resolved_at is null;
create index if not exists profile_reports_denunciante_idx
  on public.profile_reports (reporter_user_id, created_at desc);

alter table public.profile_reports enable row level security;
