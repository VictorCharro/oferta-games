import type { VercelRequest, VercelResponse } from '@vercel/node';
import sql from '../../lib/db';
import { getUserIdFromRequest } from '../../lib/auth';

export default async function handler(req: VercelRequest, res: VercelResponse) {
  if (req.method === 'OPTIONS') return res.status(200).end();
  if (req.method !== 'DELETE') return res.status(405).end();

  const userId = await getUserIdFromRequest(req);
  if (!userId) return res.status(401).json({ error: 'Não autenticado' });

  const { slug } = req.query;
  const [game] = await sql`SELECT id FROM games WHERE slug = ${slug as string}`;
  if (!game) return res.status(404).json({ error: 'Jogo não encontrado' });

  await sql`DELETE FROM favorites WHERE user_id = ${userId} AND game_id = ${game.id}`;
  return res.status(200).json({ ok: true });
}
