import postgres from 'postgres';

const sql = postgres(process.env.DATABASE_URL!, { ssl: 'require', prepare: false });

const ITAD_BASE = 'https://api.isthereanydeal.com';
const PAGE_SIZE = 100;
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
  const res = await fetch(`${ITAD_BASE}/deals/v2?country=BR&limit=${PAGE_SIZE}&offset=${offset}`, {
    headers: { 'ITAD-API-Key': API_KEY },
  });

  if (!res.ok) throw new Error(`ITAD ${res.status}: ${await res.text()}`);

  const data: DealsResponse = await res.json();
  const items = data.list ?? [];
  if (!items.length) return { count: 0, hasMore: false };

  await sql`
    INSERT INTO games (itad_id, title, slug, cover_url)
    SELECT * FROM unnest(
      ${sql.array(items.map(i => i.id))}::uuid[],
      ${sql.array(items.map(i => i.title))}::text[],
      ${sql.array(items.map(i => i.slug || toSlug(i.title)))}::text[],
      ${sql.array(items.map(i => i.assets?.banner400 ?? null))}::text[]
    ) AS t(itad_id, title, slug, cover_url)
    ON CONFLICT (itad_id) DO UPDATE
      SET title = EXCLUDED.title, cover_url = EXCLUDED.cover_url
  `;

  const dbGames = await sql<{ id: number; itad_id: string }[]>`
    SELECT id, itad_id::text FROM games WHERE itad_id = ANY(${sql.array(items.map(i => i.id))}::uuid[])
  `;

  const idMap = Object.fromEntries(dbGames.map(g => [g.itad_id, g.id]));

  const offers = items.filter(i => idMap[i.id]).map(i => ({
    game_id: idMap[i.id],
    store_name: i.deal.shop.name,
    price: i.deal.price.amount,
    regular_price: i.deal.regular?.amount ?? null,
    url: i.deal.url,
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

  return { count: offers.length, hasMore: data.hasMore };
}

async function main() {
  console.log('Starting full ITAD sync...');
  let offset = 0;
  let total = 0;

  while (true) {
    const { count, hasMore } = await syncPage(offset);
    total += count;
    console.log(`Synced ${total} deals (offset ${offset})`);
    if (!hasMore || count === 0) break;
    offset += PAGE_SIZE;
  }

  console.log(`Sync complete. Total: ${total} deals.`);
  await sql.end();
}

main().catch(err => { console.error(err); process.exit(1); });
