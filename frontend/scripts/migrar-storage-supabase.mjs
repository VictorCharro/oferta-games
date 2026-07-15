import { createClient } from '@supabase/supabase-js';

const variaveisObrigatorias = [
  'SUPABASE_ORIGEM_URL',
  'SUPABASE_ORIGEM_SERVICE_ROLE_KEY',
  'SUPABASE_DESTINO_URL',
  'SUPABASE_DESTINO_SERVICE_ROLE_KEY',
];

for (const nome of variaveisObrigatorias) {
  if (!process.env[nome]) {
    throw new Error(`Defina a variavel de ambiente ${nome} antes de executar a migracao.`);
  }
}

const origem = createClient(
  process.env.SUPABASE_ORIGEM_URL,
  process.env.SUPABASE_ORIGEM_SERVICE_ROLE_KEY,
);
const destino = createClient(
  process.env.SUPABASE_DESTINO_URL,
  process.env.SUPABASE_DESTINO_SERVICE_ROLE_KEY,
);
const bucket = 'avatars';

async function listarArquivos(caminho = '') {
  const { data, error } = await origem.storage.from(bucket).list(caminho, { limit: 1000 });
  if (error) throw new Error(`Nao foi possivel listar ${bucket}/${caminho}: ${error.message}`);

  const arquivos = [];
  for (const item of data ?? []) {
    const caminhoCompleto = caminho ? `${caminho}/${item.name}` : item.name;
    if (!item.metadata) {
      arquivos.push(...await listarArquivos(caminhoCompleto));
    } else {
      arquivos.push({ caminho: caminhoCompleto, contentType: item.metadata.mimetype });
    }
  }
  return arquivos;
}

async function garantirBucket() {
  const { data: existente, error } = await destino.storage.getBucket(bucket);
  if (existente) return;
  if (error && !error.message.toLowerCase().includes('not found')) {
    throw new Error(`Nao foi possivel consultar o bucket ${bucket}: ${error.message}`);
  }

  const { error: erroCriacao } = await destino.storage.createBucket(bucket, { public: true });
  if (erroCriacao) throw new Error(`Nao foi possivel criar o bucket ${bucket}: ${erroCriacao.message}`);
}

await garantirBucket();
const arquivos = await listarArquivos();
console.log(`${arquivos.length} arquivo(s) encontrado(s) em ${bucket}.`);

for (const [indice, arquivo] of arquivos.entries()) {
  const { data, error: erroDownload } = await origem.storage.from(bucket).download(arquivo.caminho);
  if (erroDownload) throw new Error(`Falha ao baixar ${arquivo.caminho}: ${erroDownload.message}`);

  const { error: erroUpload } = await destino.storage.from(bucket).upload(arquivo.caminho, data, {
    upsert: true,
    contentType: arquivo.contentType ?? undefined,
  });
  if (erroUpload) throw new Error(`Falha ao enviar ${arquivo.caminho}: ${erroUpload.message}`);

  console.log(`[${indice + 1}/${arquivos.length}] ${arquivo.caminho}`);
}

console.log('Migracao do bucket avatars concluida.');
