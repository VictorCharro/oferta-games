import type { VercelRequest, VercelResponse } from '@vercel/node';
import sql from '../../lib/db';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'GET') return res.status(405).end();

  const size = Math.min(200, Math.max(1, Number(req.query.size) || 20));
  const sortByRank = req.query.sort === 'rank';

  const deals = await sql`
    SELECT * FROM (
      SELECT DISTINCT ON (g.id)
        g.slug,
        g.title,
        g.cover_url AS "coverUrl",
        g.rank,
        o.store_name AS "storeName",
        o.price,
        o.regular_price AS "regularPrice",
        o.url,
        ROUND((1 - o.price / o.regular_price) * 100) AS "discountPct"
      FROM offers o
      JOIN games g ON g.id = o.game_id
      WHERE o.regular_price IS NOT NULL
        AND o.regular_price > 0
        AND o.price < o.regular_price
        AND o.price < o.regular_price * 0.99
      ORDER BY g.id, o.price ASC
    ) sub
    ORDER BY
      ${sortByRank ? sql`rank ASC NULLS LAST, "discountPct" DESC` : sql`"discountPct" DESC, rank ASC NULLS LAST`}
    LIMIT ${size}
  `;

  return res.status(200).json(deals);
}
