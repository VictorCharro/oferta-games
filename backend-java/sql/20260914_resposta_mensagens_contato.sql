-- Resposta do admin a uma mensagem do Fale conosco, entregue no site (sino de notificacoes e
-- "Minhas mensagens" em /contato), ja que ainda nao ha envio de e-mail. So mensagens de quem estava
-- logado podem ser respondidas: anonima nao tem pra quem entregar.

alter table public.contact_messages
  add column if not exists reply text check (reply is null or char_length(reply) between 1 and 2000),
  add column if not exists replied_at timestamptz,
  -- Quando o autor viu a resposta (abriu a notificacao ou "Minhas mensagens").
  add column if not exists reply_read_at timestamptz;

create index if not exists contact_messages_respostas_nao_lidas_idx
  on public.contact_messages (user_id) where replied_at is not null and reply_read_at is null;
