import type { VercelRequest, VercelResponse } from '@vercel/node';
import sql from '../../lib/db';
import { getUserIdFromRequest } from '../../lib/auth';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method === 'OPTIONS') return res.status(200).end();

  const userId = await getUserIdFromRequest(req);
  if (!userId) return res.status(401).json({ error: 'Não autenticado' });

  if (req.method === 'GET') {
    const favorites = await sql`
      SELECT
        g.slug,
        g.title,
        g.cover_url AS "coverUrl",
        MIN(o.price) AS "minPrice",
        MAX(o.regular_price) AS "regularPrice",
        f.created_at AS "favoritedAt"
      FROM favorites f
      JOIN games g ON g.id = f.game_id
      LEFT JOIN offers o ON o.game_id = g.id
      WHERE f.user_id = ${userId}
      GROUP BY g.id, g.slug, g.title, g.cover_url, f.created_at
      ORDER BY f.created_at DESC
    `;
    return res.status(200).json(favorites);
  }

  if (req.method === 'POST') {
    const { slug } = req.body ?? {};
    if (!slug) return res.status(400).json({ error: 'slug é obrigatório' });

    const [game] = await sql`SELECT id FROM games WHERE slug = ${slug}`;
    if (!game) return res.status(404).json({ error: 'Jogo não encontrado' });

    await sql`
      INSERT INTO favorites (user_id, game_id)
      VALUES (${userId}, ${game.id})
      ON CONFLICT (user_id, game_id) DO NOTHING
    `;
    return res.status(201).json({ ok: true });
  }

  return res.status(405).end();
}
