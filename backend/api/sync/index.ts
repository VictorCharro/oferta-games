import type { VercelRequest, VercelResponse } from '@vercel/node';
import sql from '../../lib/db';

const ITAD_BASE = 'https://api.isthereanydeal.com';
const PAGE_SIZE = 50;
const MAX_PAGES = 10;

interface ItadGame {
  id: string;
  slug: string;
  title: string;
  assets?: { banner400?: string };
}

interface ItadPriceResult {
  id: string;
  deals: Array<{
    shop: { id: number; name: string };
    price: { amount: number; currency: string };
    regular?: { amount: number; currency: string };
    url: string;
  }>;
}

async function fetchGames(apiKey: string, limit: number, offset: number): Promise<ItadGame[]> {
  const res = await fetch(`${ITAD_BASE}/games/search/v1?limit=${limit}&offset=${offset}`, {
    headers: { 'ITAD-API-Key': apiKey },
  });
  if (!res.ok) return [];
  return res.json();
}

async function fetchPrices(apiKey: string, ids: string[]): Promise<ItadPriceResult[]> {
  const res = await fetch(`${ITAD_BASE}/games/prices/v3?country=BR&shops=61,35,16`, {
    method: 'POST',
    headers: { 'ITAD-API-Key': apiKey, 'Content-Type': 'application/json' },
    body: JSON.stringify(ids),
  });
  if (!res.ok) return [];
  return res.json();
}

function toSlug(title: string): string {
  return title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
}

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'POST') return res.status(405).end();

  const syncKey = req.headers['x-sync-key'];
  if (syncKey !== process.env.SYNC_SECRET_KEY) {
    return res.status(401).json({ error: 'Unauthorized' });
  }

  const apiKey = process.env.ITAD_API_KEY!;

  for (let page = 0; page < MAX_PAGES; page++) {
    const games = await fetchGames(apiKey, PAGE_SIZE, page * PAGE_SIZE);
    if (!games.length) break;

    for (const g of games) {
      const slug = g.slug || toSlug(g.title);
      await sql`
        INSERT INTO games (itad_id, title, slug, cover_url)
        VALUES (${g.id}::uuid, ${g.title}, ${slug}, ${g.assets?.banner400 ?? null})
        ON CONFLICT (itad_id) DO UPDATE
          SET title = EXCLUDED.title,
              cover_url = EXCLUDED.cover_url
      `;
    }

    const ids = games.map(g => g.id);
    const prices = await fetchPrices(apiKey, ids);

    for (const result of prices) {
      const [game] = await sql`SELECT id FROM games WHERE itad_id = ${result.id}::uuid`;
      if (!game) continue;

      for (const deal of result.deals) {
        await sql`
          INSERT INTO offers (game_id, source, store_name, price, regular_price, currency, url, updated_at)
          VALUES (
            ${game.id}, 'itad', ${deal.shop.name},
            ${deal.price.amount}, ${deal.regular?.amount ?? null},
            'BRL', ${deal.url}, NOW()
          )
          ON CONFLICT (game_id, source, store_name) DO UPDATE
            SET price = EXCLUDED.price,
                regular_price = EXCLUDED.regular_price,
                url = EXCLUDED.url,
                updated_at = NOW()
        `;
      }
    }

    console.log(`Synced page ${page + 1}`);
  }

  return res.status(200).json({ ok: true });
}
