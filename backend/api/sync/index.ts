import type { VercelRequest, VercelResponse } from '@vercel/node';
import sql from '../../lib/db';

const ITAD_BASE = 'https://api.isthereanydeal.com';
const PAGE_SIZE = 50;

interface Deal {
  id: string;
  slug: string;
  title: string;
  assets?: { banner400?: string };
  deal: {
    shop: { id: number; name: string };
    price: { amount: number; currency: string };
    regular?: { amount: number; currency: string };
    url: string;
  };
}

interface DealsResponse {
  nextOffset: number;
  hasMore: boolean;
  list: Deal[];
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'POST') return res.status(405).end();

  const syncKey = req.headers['x-sync-key'];
  if (syncKey !== process.env.SYNC_SECRET_KEY) {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  const page = Math.max(0, Number(req.query.page) || 0);
  const offset = page * PAGE_SIZE;

  const response = await fetch(
    `${ITAD_BASE}/deals/v2?country=BR&shops=50,6,36,37,24,42,19,61,16,4,52,48,62&limit=${PAGE_SIZE}&offset=${offset}`,
    { headers: { 'ITAD-API-Key': process.env.ITAD_API_KEY! } }
  );

  if (!response.ok) {
    const err = await response.text();
    return res.status(500).json({ error: `ITAD error ${response.status}`, detail: err });
  }

  const data: DealsResponse = await response.json();
  const items = data.list ?? [];

  if (!items.length) {
    return res.status(200).json({ ok: true, synced: 0, hasMore: false });
  }

  // Batch upsert games
  const games = items.map((item, i) => ({
    itad_id: item.id,
    title: item.title,
    slug: item.slug || item.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, ''),
    cover_url: item.assets?.banner400 ?? null,
    rank: offset + i,
  }));

  await sql`
    INSERT INTO games (itad_id, title, slug, cover_url, rank)
    SELECT * FROM unnest(
      ${sql.array(games.map(g => g.itad_id))}::uuid[],
      ${sql.array(games.map(g => g.title))}::text[],
      ${sql.array(games.map(g => g.slug))}::text[],
      ${sql.array(games.map(g => g.cover_url))}::text[],
      ${sql.array(games.map(g => g.rank))}::integer[]
    ) AS t(itad_id, title, slug, cover_url, rank)
    ON CONFLICT (itad_id) DO UPDATE
      SET title = EXCLUDED.title, cover_url = EXCLUDED.cover_url, rank = EXCLUDED.rank
  `;

  // Fetch inserted game ids
  const dbGames = await sql<{ id: number; itad_id: string }[]>`
    SELECT id, itad_id::text FROM games WHERE itad_id = ANY(${sql.array(games.map(g => g.itad_id))}::uuid[])
  `;

  const idMap = Object.fromEntries(dbGames.map(g => [g.itad_id, g.id]));

  // Batch upsert offers
  const offers = items
    .filter(item => idMap[item.id])
    .map(item => ({
      game_id: idMap[item.id],
      store_name: item.deal.shop.name,
      price: item.deal.price.amount,
      regular_price: item.deal.regular?.amount ?? null,
      url: item.deal.url,
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

  return res.status(200).json({ ok: true, synced: offers.length, hasMore: data.hasMore, nextPage: page + 1 });
}
