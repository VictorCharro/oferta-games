import postgres from 'postgres';
import { steamCoverFromUrl } from '../lib/steam-cover';

const sql = postgres(process.env.DATABASE_URL!, { ssl: 'require', prepare: false });

const ITAD_BASE = 'https://api.isthereanydeal.com';
const PAGE_SIZE = 100;
const MAX_DEALS = 15000;
const API_KEY = process.env.ITAD_API_KEY!;

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
  hasMore: boolean;
  list: Deal[];
}

function toSlug(title: string) {
  return title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '');
}

async function syncPage(offset: number): Promise<{ count: number; hasMore: boolean }> {
  const res = await fetch(`${ITAD_BASE}/deals/v2?country=BR&shops=50,6,36,37,24,42,19,61,16,4,52,48,62&sort=rank&limit=${PAGE_SIZE}&offset=${offset}`, {
    headers: { 'ITAD-API-Key': API_KEY },
  });

  if (!res.ok) throw new Error(`ITAD ${res.status}: ${await res.text()}`);

  const data: DealsResponse = await res.json();
  const items = data.list ?? [];
  if (!items.length) return { count: 0, hasMore: false };

  const now = new Date().toISOString();

  for (const [i, item] of items.entries()) {
    const slug = item.slug || toSlug(item.title);
    const coverUrl = item.assets?.banner400 ?? (item.deal.shop.name === 'Steam' ? steamCoverFromUrl(item.deal.url) : null);
    const rank = offset + i;

    const [game] = await sql<{ id: number }[]>`
      INSERT INTO games (itad_id, title, slug, cover_url, rank)
      VALUES (${item.id}::uuid, ${item.title}, ${slug}, ${coverUrl}, ${rank})
      ON CONFLICT (itad_id) DO UPDATE
        SET title = EXCLUDED.title,
            cover_url = COALESCE(EXCLUDED.cover_url, games.cover_url),
            rank = EXCLUDED.rank
      RETURNING id
    `;

    await sql`
      INSERT INTO offers (game_id, source, store_name, price, regular_price, currency, url, updated_at)
      VALUES (
        ${game.id}, 'itad', ${item.deal.shop.name},
        ${item.deal.price.amount}, ${item.deal.regular?.amount ?? null},
        'BRL', ${item.deal.url}, ${now}::timestamptz
      )
      ON CONFLICT (game_id, source, store_name) DO UPDATE
        SET price = EXCLUDED.price,
            regular_price = EXCLUDED.regular_price,
            url = EXCLUDED.url,
            updated_at = EXCLUDED.updated_at
    `;
  }

  return { count: items.length, hasMore: data.hasMore };
}

async function main() {
  console.log('Starting full ITAD sync...');
  let offset = 0;
  let total = 0;

  while (true) {
    const { count, hasMore } = await syncPage(offset);
    total += count;
    console.log(`Synced ${total} deals (offset ${offset})`);
    if (!hasMore || count === 0 || total >= MAX_DEALS) break;
    offset += PAGE_SIZE;
  }

  console.log(`Sync complete. Total: ${total} deals.`);
  await sql.end();
}

main().catch(err => { console.error(err); process.exit(1); });
