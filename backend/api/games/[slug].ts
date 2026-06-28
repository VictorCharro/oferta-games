import type { VercelRequest, VercelResponse } from '@vercel/node';
import sql from '../../lib/db';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'GET') return res.status(405).end();

  const { slug } = req.query;

  const [game] = await sql`
    SELECT id, slug, title, cover_url AS "coverUrl"
    FROM games
    WHERE slug = ${slug as string}
  `;

  if (!game) return res.status(404).json({ error: 'Game not found' });

  const offers = await sql`
    SELECT store_name AS "storeName", price, regular_price AS "regularPrice", currency, url
    FROM offers
    WHERE game_id = ${game.id}
    ORDER BY price ASC
  `;

  return res.status(200).json({ ...game, offers });
}
