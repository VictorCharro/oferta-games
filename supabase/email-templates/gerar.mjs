// Gera os templates de e-mail do Supabase Auth (issue #20) a partir de um layout unico.
// Uso: node supabase/email-templates/gerar.mjs  -> escreve os .html nesta pasta.
// Depois colar cada um em Supabase > Authentication > Emails > Templates (ver README.md).
//
// Regras de e-mail HTML: tabela + estilo inline (Outlook/Gmail ignoram <style> e flex), largura
// fixa de 560px, fundo claro (modo escuro do cliente inverte sozinho), botao com fallback do link
// em texto. Variaveis {{ .X }} sao do Go template do Supabase e nao podem ser alteradas.
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const pasta = dirname(fileURLToPath(import.meta.url));
// Trocar pelo dominio proprio na migracao (#19).
const SITE = 'https://ofertagames.vercel.app';

function layout({ preheader, titulo, paragrafos, botao, link = '{{ .ConfirmationURL }}', codigo, aviso }) {
  const blocoBotao = botao ? `
          <tr><td align="center" style="padding:8px 0 28px;">
            <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
              <td align="center" bgcolor="#29A8E0" style="border-radius:10px;">
                <a href="${link}" target="_blank" style="display:inline-block;padding:14px 32px;font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:bold;color:#ffffff;text-decoration:none;border-radius:10px;">${botao}</a>
              </td>
            </tr></table>
          </td></tr>
          <tr><td style="padding:0 0 24px;font-family:Arial,Helvetica,sans-serif;font-size:13px;line-height:20px;color:#64748b;">
            Se o botão não funcionar, copie e cole este endereço no navegador:<br>
            <a href="${link}" target="_blank" style="color:#1a8fc4;word-break:break-all;">${link}</a>
          </td></tr>` : '';
  const blocoCodigo = codigo ? `
          <tr><td align="center" style="padding:4px 0 28px;">
            <div style="display:inline-block;padding:14px 28px;border-radius:10px;background:#f1f5f9;border:1px solid #e2e8f0;font-family:'Courier New',Courier,monospace;font-size:30px;font-weight:bold;letter-spacing:8px;color:#0f172a;">${codigo}</div>
          </td></tr>` : '';

  return `<!DOCTYPE html>
<html lang="pt-BR">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="color-scheme" content="light">
  <title>${titulo}</title>
</head>
<body style="margin:0;padding:0;background:#eef2f7;">
  <div style="display:none;max-height:0;overflow:hidden;opacity:0;color:transparent;">${preheader}</div>
  <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" bgcolor="#eef2f7" style="background:#eef2f7;">
    <tr><td align="center" style="padding:32px 16px;">
      <table role="presentation" width="560" cellpadding="0" cellspacing="0" border="0" style="width:100%;max-width:560px;">
        <tr><td align="center" style="padding:0 0 20px;">
          <a href="${SITE}" target="_blank"><img src="${SITE}/logo.png" width="200" alt="Oferta Games" style="display:block;width:200px;max-width:60%;height:auto;border:0;"></a>
        </td></tr>
        <tr><td bgcolor="#ffffff" style="background:#ffffff;border-radius:16px;border-top:5px solid #29A8E0;">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
            <tr><td style="padding:36px 36px 0;">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                <tr><td style="padding:0 0 16px;font-family:Arial,Helvetica,sans-serif;font-size:24px;line-height:32px;font-weight:bold;color:#0f172a;">${titulo}</td></tr>
                ${paragrafos.map(p => `<tr><td style="padding:0 0 16px;font-family:Arial,Helvetica,sans-serif;font-size:16px;line-height:25px;color:#334155;">${p}</td></tr>`).join('\n                ')}
                ${blocoCodigo}${blocoBotao}
              </table>
            </td></tr>
            <tr><td style="padding:0 36px 32px;">
              <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0">
                <tr><td style="padding:16px 18px;border-radius:10px;background:#f8fafc;font-family:Arial,Helvetica,sans-serif;font-size:13px;line-height:20px;color:#64748b;">
                  ${aviso}
                </td></tr>
              </table>
            </td></tr>
          </table>
        </td></tr>
        <tr><td align="center" style="padding:24px 16px 0;font-family:Arial,Helvetica,sans-serif;font-size:12px;line-height:19px;color:#94a3b8;">
          <strong style="color:#64748b;">Oferta Games</strong> · Compare preços de jogos e ache a melhor oferta<br>
          Você recebeu este e-mail por causa de uma ação na sua conta em <a href="${SITE}" target="_blank" style="color:#64748b;">ofertagames</a>.<br>
          <a href="${SITE}/privacidade" target="_blank" style="color:#94a3b8;">Política de Privacidade</a> · <a href="${SITE}/contato" target="_blank" style="color:#94a3b8;">Fale conosco</a>
        </td></tr>
      </table>
    </td></tr>
  </table>
</body>
</html>
`;
}

const AVISO_NAO_FOI_VOCE = 'Não foi você? Pode ignorar este e-mail com segurança: nada muda na sua conta sem que alguém clique no link.';

const templates = {
  'confirmar-cadastro': {
    assunto: 'Confirme seu e-mail no Oferta Games',
    html: layout({
      preheader: 'Falta só um clique pra ativar sua conta.',
      titulo: 'Boas-vindas ao Oferta Games! 🎮',
      paragrafos: [
        'Falta só confirmar que este e-mail é seu. Depois disso você já pode monitorar jogos, receber alerta quando o preço cair e montar seu perfil com a sua biblioteca da Steam e do Xbox.',
      ],
      botao: 'Confirmar meu e-mail',
      aviso: 'Não criou uma conta? Pode ignorar este e-mail: sem a confirmação, a conta não é ativada.',
    }),
  },
  'redefinir-senha': {
    assunto: 'Redefina sua senha do Oferta Games',
    html: layout({
      preheader: 'Use o link pra criar uma senha nova. Ele vale por tempo limitado.',
      titulo: 'Vamos criar uma senha nova',
      paragrafos: [
        'Recebemos um pedido pra redefinir a senha da conta <strong>{{ .Email }}</strong>. Clique no botão abaixo pra escolher uma senha nova.',
        'Por segurança, o link vale por tempo limitado e só pode ser usado uma vez.',
      ],
      botao: 'Criar senha nova',
      aviso: 'Não pediu isso? Ignore este e-mail: sua senha atual continua valendo. Se isso se repetir, vale trocar a senha nas Configurações.',
    }),
  },
  'link-de-acesso': {
    assunto: 'Seu link de acesso ao Oferta Games',
    html: layout({
      preheader: 'Entre na sua conta com um clique.',
      titulo: 'Seu link de acesso',
      paragrafos: ['Clique no botão abaixo pra entrar na sua conta <strong>{{ .Email }}</strong>. O link vale por tempo limitado e só pode ser usado uma vez.'],
      botao: 'Entrar no Oferta Games',
      aviso: AVISO_NAO_FOI_VOCE,
    }),
  },
  'trocar-email': {
    assunto: 'Confirme a troca de e-mail no Oferta Games',
    html: layout({
      preheader: 'Confirme o novo endereço da sua conta.',
      titulo: 'Confirme seu novo e-mail',
      paragrafos: ['Pediram pra trocar o e-mail da conta de <strong>{{ .Email }}</strong> para <strong>{{ .NewEmail }}</strong>. Clique no botão pra confirmar a troca.'],
      botao: 'Confirmar troca de e-mail',
      aviso: 'Não pediu essa troca? Ignore este e-mail e troque sua senha nas Configurações por precaução.',
    }),
  },
  'convite': {
    assunto: 'Você foi convidado pro Oferta Games',
    html: layout({
      preheader: 'Aceite o convite e crie sua conta.',
      titulo: 'Você recebeu um convite',
      paragrafos: ['Você foi convidado pra criar uma conta no Oferta Games, o site que compara preços de jogos nas lojas e avisa quando cai.'],
      botao: 'Aceitar convite',
      aviso: 'Não esperava esse convite? Pode ignorar este e-mail.',
    }),
  },
  'codigo-confirmacao': {
    assunto: 'Seu código de confirmação do Oferta Games',
    html: layout({
      preheader: 'Use este código pra confirmar a ação na sua conta.',
      titulo: 'Seu código de confirmação',
      paragrafos: ['Use o código abaixo pra confirmar a ação que você está fazendo na sua conta. Ele vale por poucos minutos.'],
      codigo: '{{ .Token }}',
      aviso: 'Não foi você? Ninguém consegue usar este código sem acesso à sua conta, mas vale trocar a senha nas Configurações.',
    }),
  },
};

for (const [nome, { html }] of Object.entries(templates)) {
  writeFileSync(join(pasta, `${nome}.html`), html);
}
writeFileSync(join(pasta, 'assuntos.json'), JSON.stringify(Object.fromEntries(Object.entries(templates).map(([n, t]) => [n, t.assunto])), null, 2) + '\n');
console.log('Gerados:', Object.keys(templates).join(', '));
