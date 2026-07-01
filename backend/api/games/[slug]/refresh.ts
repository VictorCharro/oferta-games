import type { VercelRequest, VercelResponse } from '@vercel/node';
import sql from '../../../lib/db';
import { steamCoverFromUrl } from '../../../lib/steam-cover';

const ITAD_BASE = 'https://api.isthereanydeal.com';

interface PriceResult {
  id: string;
  deals: Array<{
    shop: { id: number; name: string };
    price: { amount: number; currency: string };
    regular?: { amount: number; currency: string };
    url: string;
  }>;
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'POST') return res.status(405).end();

  const { slug } = req.query;

  const [game] = await sql<{ id: number; itad_id: string; coverUrl: string | null }[]>`
    SELECT id, itad_id::text, cover_url AS "coverUrl" FROM games WHERE slug = ${slug as string}
  `;

  if (!game) return res.status(404).json({ error: 'Game not found' });
  if (!game.itad_id) return res.status(400).json({ error: 'Game has no ITAD id' });

  const response = await fetch(`${ITAD_BASE}/games/prices/v3?country=BR`, {
    method: 'POST',
    headers: {
      'ITAD-API-Key': process.env.ITAD_API_KEY!,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify([game.itad_id]),
  });

  if (!response.ok) {
    return res.status(500).json({ error: `ITAD error ${response.status}` });
  }

  const results: PriceResult[] = await response.json();
  const result = results[0];

  if (!result?.deals?.length) {
    return res.status(200).json({ ok: true, updated: 0 });
  }

  const offers = result.deals.map(d => ({
    game_id: game.id,
    store_name: d.shop.name,
    price: d.price.amount,
    regular_price: d.regular?.amount ?? null,
    url: d.url,
  }));

  await sql`
    INSERT INTO offers (game_id, source, store_name, price, regular_price, currency, url, updated_at)
    SELECT * FROM unnest(
      ${sql.array(offers.map(o => o.game_id))}::bigint[],
      ${sql.array(offers.map(() => 'itad'))}::text[],
      ${sql.array(offers.map(o => o.store_name))}::text[],
      ${sql.array(offers.map(o => o.price))}::numeric[],
      ${sql.array(offers.map(o => o.regular_price))}::numeric[],
      ${sql.array(offers.map(() => 'BRL'))}::text[],
      ${sql.array(offers.map(o => o.url))}::text[],
      ${sql.array(offers.map(() => new Date().toISOString()))}::timestamptz[]
    ) AS t(game_id, source, store_name, price, regular_price, currency, url, updated_at)
    ON CONFLICT (game_id, source, store_name) DO UPDATE
      SET price = EXCLUDED.price,
          regular_price = EXCLUDED.regular_price,
          url = EXCLUDED.url,
          updated_at = EXCLUDED.updated_at
  `;

  if (!game.coverUrl) {
    const steamOffer = result.deals.find(d => d.shop.name === 'Steam');
    const cover = steamOffer ? steamCoverFromUrl(steamOffer.url) : null;
    if (cover) {
      await sql`UPDATE games SET cover_url = ${cover} WHERE id = ${game.id}`;
    }
  }

  return res.status(200).json({ ok: true, updated: offers.length });
}
