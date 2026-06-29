import type { VercelRequest, VercelResponse } from '@vercel/node';
import sql from '../../lib/db';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'GET') return res.status(405).end();

  const page = Math.max(0, Number(req.query.page) || 0);
  const size = Math.min(100, Math.max(1, Number(req.query.size) || 20));
  const offset = page * size;

  const games = await sql`
    SELECT
      g.slug,
      g.title,
      g.cover_url AS "coverUrl",
      MIN(o.price) AS "minPrice"
    FROM games g
    LEFT JOIN offers o ON o.game_id = g.id
    GROUP BY g.id, g.slug, g.title, g.cover_url
    ORDER BY
      (MIN(o.price) IS NOT NULL) DESC,
      g.id ASC
    LIMIT ${size} OFFSET ${offset}
  `;

  return res.status(200).json(games);
}
