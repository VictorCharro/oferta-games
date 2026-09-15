# Templates de e-mail do Supabase Auth

E-mails de conta em pt-BR com a identidade do site (issue #20). Os `.html` sao gerados por
`gerar.mjs` a partir de um layout unico: editar la e rodar `node supabase/email-templates/gerar.mjs`.
Nao editar os `.html` na mao.

## Como aplicar

Supabase > projeto `oferta-games-sp` > **Authentication > Emails > Templates**. Pra cada aba,
colar o **assunto** e trocar o corpo inteiro pelo conteudo do arquivo (aba "Source"/HTML):

| Aba no Supabase | Arquivo | Assunto |
|---|---|---|
| Confirm signup | `confirmar-cadastro.html` | Confirme seu e-mail no Oferta Games |
| Invite user | `convite.html` | Você foi convidado pro Oferta Games |
| Magic Link | `link-de-acesso.html` | Seu link de acesso ao Oferta Games |
| Change Email Address | `trocar-email.html` | Confirme a troca de e-mail no Oferta Games |
| Reset Password | `redefinir-senha.html` | Redefina sua senha do Oferta Games |
| Reauthentication | `codigo-confirmacao.html` | Seu código de confirmação do Oferta Games |

Os assuntos tambem estao em `assuntos.json`.

## Observacoes

- As variaveis `{{ .ConfirmationURL }}`, `{{ .Email }}`, `{{ .NewEmail }}` e `{{ .Token }}` sao do
  Supabase e precisam ficar exatamente assim.
- O rodape "powered by Supabase" e o remetente `noreply@mail.app.supabase.io` so saem com SMTP
  proprio (issue #20). O servidor padrao do Supabase tambem limita o envio a poucos e-mails por hora.
- Imagem do logo e links apontam pra `https://ofertagames.vercel.app`: trocar `SITE` no
  `gerar.mjs` na migracao pro dominio proprio (#19) e colar de novo.
