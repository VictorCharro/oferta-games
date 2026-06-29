import type { VercelRequest, VercelResponse } from '@vercel/node';
import sql from '../../lib/db';

const ITAD_BASE = 'https://api.isthereanydeal.com';
const PAGE_SIZE = 100;
const MAX_PAGES = 20;

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

  const apiKey = process.env.ITAD_API_KEY!;
  let synced = 0;

  for (let page = 0; page < MAX_PAGES; page++) {
    const offset = page * PAGE_SIZE;
    const response = await fetch(
      `${ITAD_BASE}/deals/v2?country=BR&limit=${PAGE_SIZE}&offset=${offset}`,
      { headers: { 'ITAD-API-Key': apiKey } }
    );

    if (!response.ok) {
      const err = await response.text();
      return res.status(500).json({ error: `ITAD error ${response.status}`, detail: err });
    }

    const data: DealsResponse = await response.json();
    if (!data.list?.length) break;

    for (const item of data.list) {
      const slug = item.slug || item.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
      const coverUrl = item.assets?.banner400 ?? null;

      const [game] = await sql`
        INSERT INTO games (itad_id, title, slug, cover_url)
        VALUES (${item.id}::uuid, ${item.title}, ${slug}, ${coverUrl})
        ON CONFLICT (itad_id) DO UPDATE
          SET title = EXCLUDED.title, cover_url = EXCLUDED.cover_url
        RETURNING id
      `;

      await sql`
        INSERT INTO offers (game_id, source, store_name, price, regular_price, currency, url, updated_at)
        VALUES (
          ${game.id}, 'itad', ${item.deal.shop.name},
          ${item.deal.price.amount}, ${item.deal.regular?.amount ?? null},
          'BRL', ${item.deal.url}, NOW()
        )
        ON CONFLICT (game_id, source, store_name) DO UPDATE
          SET price = EXCLUDED.price,
              regular_price = EXCLUDED.regular_price,
              url = EXCLUDED.url,
              updated_at = NOW()
      `;

      synced++;
    }

    if (!data.hasMore) break;
  }

  return res.status(200).json({ ok: true, synced });
}
