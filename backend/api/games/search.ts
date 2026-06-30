import type { VercelRequest, VercelResponse } from '@vercel/node';
import sql from '../../lib/db';

const ITAD_BASE = 'https://api.isthereanydeal.com';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'GET') return res.status(405).end();

  const q = (req.query.q as string)?.trim();
  if (!q || q.length < 2) return res.status(400).json({ error: 'Query too short' });

  // Busca primeiro no banco local
  const local = await sql`
    SELECT g.slug, g.title, g.cover_url AS "coverUrl",
      MIN(o.price) AS "minPrice", MAX(o.regular_price) AS "regularPrice"
    FROM games g
    LEFT JOIN offers o ON o.game_id = g.id
    WHERE g.title ILIKE ${'%' + q + '%'}
    GROUP BY g.id, g.slug, g.title, g.cover_url
    ORDER BY g.rank ASC NULLS LAST, g.title
    LIMIT 20
  `;

  if (local.length > 0) return res.status(200).json(local);

  // Se não achou no banco, busca na ITAD e insere
  const response = await fetch(
    `${ITAD_BASE}/games/search/v1?title=${encodeURIComponent(q)}&limit=10`,
    { headers: { 'ITAD-API-Key': process.env.ITAD_API_KEY! } }
  );

  if (!response.ok) return res.status(200).json([]);

  const itadGames: Array<{
    id: string;
    slug: string;
    title: string;
    assets?: { banner400?: string };
  }> = await response.json();

  if (!itadGames.length) return res.status(200).json([]);

  // Insere os jogos encontrados no banco
  for (const g of itadGames) {
    const slug = g.slug || g.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
    await sql`
      INSERT INTO games (itad_id, title, slug, cover_url)
      VALUES (${g.id}::uuid, ${g.title}, ${slug}, ${g.assets?.banner400 ?? null})
      ON CONFLICT (itad_id) DO UPDATE
        SET title = EXCLUDED.title, cover_url = EXCLUDED.cover_url
    `;
  }

  // Retorna os jogos inseridos (sem preço ainda, preço vem quando o usuário entrar no jogo)
  const inserted = await sql`
    SELECT g.slug, g.title, g.cover_url AS "coverUrl",
      MIN(o.price) AS "minPrice", MAX(o.regular_price) AS "regularPrice"
    FROM games g
    LEFT JOIN offers o ON o.game_id = g.id
    WHERE g.itad_id = ANY(${sql.array(itadGames.map(g => g.id))}::uuid[])
    GROUP BY g.id, g.slug, g.title, g.cover_url
    ORDER BY g.title
  `;

  return res.status(200).json(inserted);
}
