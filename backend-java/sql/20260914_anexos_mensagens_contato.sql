-- Anexos (imagem ou video) do Fale conosco, pra evidencia de bug. O arquivo em si fica no disco da
-- VM Oracle (volume anexos_contato, ver deploy/oracle/compose.yml), nao no Supabase Storage: la o
-- plano gratis tem 1 GB e 50 MB por arquivo, e video estouraria isso rapido. Aqui fica so o registro.
--
-- Sem policy de RLS: so o backend le e escreve. Os arquivos so sao servidos pro admin.

create table if not exists public.contact_attachments (
  id bigserial primary key,
  message_id bigint not null references public.contact_messages(id) on delete cascade,
  -- Nome no disco (uuid + extensao), gerado pelo backend; nunca o nome enviado pelo usuario.
  stored_name text not null unique,
  content_type text not null check (content_type in ('image/png', 'image/jpeg', 'image/webp', 'image/gif', 'video/mp4', 'video/webm', 'video/quicktime')),
  size_bytes bigint not null check (size_bytes > 0),
  original_name text check (original_name is null or char_length(original_name) <= 120),
  created_at timestamptz not null default now()
);

create index if not exists contact_attachments_mensagem_idx on public.contact_attachments (message_id);

alter table public.contact_attachments enable row level security;
