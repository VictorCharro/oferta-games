import type { VercelRequest, VercelResponse } from '@vercel/node';
import sql from '../../lib/db';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'GET') return res.status(405).end();

  const page = Math.max(0, Number(req.query.page) || 0);
  const size = Math.min(100, Math.max(1, Number(req.query.size) || 20));
  const offset = page * size;
  const sort = (req.query.sort as string) || 'rank';
  const minPrice = req.query.minPrice ? Number(req.query.minPrice) : null;
  const maxPrice = req.query.maxPrice ? Number(req.query.maxPrice) : null;
  const type = (req.query.type as string) || 'all'; // 'all' | 'game' | 'dlc'
  const q = (req.query.q as string)?.trim() || null;

  // Ordenação padrão (rank): top 200 com desconto ativo sobem ao topo ordenados por desconto,
  // restante vem por popularidade — faz o catálogo rotacionar conforme promoções do dia
  const orderBy =
    sort === 'discount' ? sql`ROUND((1 - MIN(o.price) / NULLIF(MAX(o.regular_price), 0)) * 100) DESC NULLS LAST, g.rank ASC NULLS LAST` :
    sort === 'price_asc' ? sql`MIN(o.price) ASC NULLS LAST` :
    sort === 'price_desc' ? sql`MIN(o.price) DESC NULLS LAST` :
    sql`
      CASE
        WHEN g.rank <= 200
          AND MIN(o.price) IS NOT NULL
          AND MAX(o.regular_price) IS NOT NULL
          AND MIN(o.price) < MAX(o.regular_price) * 0.99
        THEN 0 ELSE 1
      END ASC,
      CASE
        WHEN g.rank <= 200
          AND MIN(o.price) IS NOT NULL
          AND MAX(o.regular_price) IS NOT NULL
          AND MIN(o.price) < MAX(o.regular_price) * 0.99
        THEN ROUND((1 - MIN(o.price) / NULLIF(MAX(o.regular_price), 0)) * 100)
        ELSE NULL
      END DESC NULLS LAST,
      g.rank ASC NULLS LAST,
      g.id ASC
    `;

  const titleIsDlc = sql`(g.title ILIKE '%DLC%' OR g.title ILIKE '%Season Pass%' OR g.title ILIKE '%Soundtrack%' OR g.title ILIKE '% OST%' OR g.title ILIKE '%Art Book%' OR g.title ILIKE '%Skin Set%' OR g.title ILIKE '%Skin Pack%' OR g.title ILIKE '%Booster Pack%' OR g.title ILIKE '%Expansion%' OR g.title ILIKE '%Add-on%')`;

  // Prefere a classificação real vinda da Steam (g.is_dlc); cai para a heurística
  // por título só quando o jogo ainda não foi classificado (is_dlc IS NULL).
  const typeFilter =
    type === 'dlc' ? sql`AND (g.is_dlc = true OR (g.is_dlc IS NULL AND ${titleIsDlc}))` :
    type === 'game' ? sql`AND (g.is_dlc = false OR (g.is_dlc IS NULL AND NOT ${titleIsDlc}))` :
    sql``;

  const priceFilter =
    minPrice !== null && maxPrice !== null ? sql`HAVING MIN(o.price) >= ${minPrice} AND MIN(o.price) <= ${maxPrice}` :
    minPrice !== null ? sql`HAVING MIN(o.price) >= ${minPrice}` :
    maxPrice !== null ? sql`HAVING MIN(o.price) <= ${maxPrice}` :
    sql``;

  const searchFilter = q ? sql`AND g.title ILIKE ${'%' + q + '%'}` : sql``;

  const games = await sql`
    SELECT
      g.slug,
      g.title,
      g.cover_url AS "coverUrl",
      g.is_dlc AS "isDlc",
      MIN(o.price) AS "minPrice",
      MAX(o.regular_price) AS "regularPrice"
    FROM games g
    LEFT JOIN offers o ON o.game_id = g.id
    WHERE 1=1
    ${typeFilter}
    ${searchFilter}
    GROUP BY g.id, g.slug, g.title, g.cover_url, g.is_dlc
    ${priceFilter}
    ORDER BY ${orderBy}
    LIMIT ${size} OFFSET ${offset}
  `;

  return res.status(200).json(games);
}
