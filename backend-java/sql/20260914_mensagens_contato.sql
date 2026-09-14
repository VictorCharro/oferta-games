-- Fale conosco (/contato): elogio, sugestao, problema, denuncia e outros, pra orientar o desenvolvimento.
--
-- Aceita mensagem sem login de proposito: quem nao consegue entrar (bug no login, confirmacao de
-- e-mail que nao chega) e justamente quem mais precisa desse canal. O freio contra spam fica no
-- backend (ControladorContato): teto por conta, teto global pras anonimas e limite por IP.
--
-- Sem policy de RLS: so o backend (role postgres) le e escreve; o navegador nunca acessa direto.

create table if not exists public.contact_messages (
  id bigserial primary key,
  kind text not null check (kind in ('elogio', 'sugestao', 'problema', 'denuncia', 'outro')),
  message text not null check (char_length(message) between 10 and 2000),
  -- E-mail pra resposta: opcional. Logado, vem da conta; anonimo, so se a pessoa digitar.
  reply_email text check (reply_email is null or char_length(reply_email) <= 254),
  -- set null: quem mandou pode excluir a conta sem apagar o que ja relatou.
  user_id uuid references auth.users(id) on delete set null,
  -- Pagina de onde a pessoa veio (so o caminho), ajuda a reproduzir problema.
  page_path text check (page_path is null or char_length(page_path) <= 300),
  created_at timestamptz not null default now(),
  resolved_at timestamptz
);

create index if not exists contact_messages_abertas_idx
  on public.contact_messages (created_at desc) where resolved_at is null;
create index if not exists contact_messages_usuario_idx
  on public.contact_messages (user_id, created_at desc);

alter table public.contact_messages enable row level security;
