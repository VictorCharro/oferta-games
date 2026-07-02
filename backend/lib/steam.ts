export function extractSteamAppId(url: string): string | null {
  const match = url.match(/store\.steampowered\.com\/app\/(\d+)/);
  return match ? match[1] : null;
}

export function steamCoverFromUrl(url: string): string | null {
  const appid = extractSteamAppId(url);
  return appid ? `https://cdn.akamai.steamstatic.com/steam/apps/${appid}/header.jpg` : null;
}

// URLs de oferta da ITAD são sempre um redirecionador próprio (itad.link/...),
// que costuma passar por mais um hop antes de chegar na Steam de verdade —
// às vezes numa página de bundle em vez de /app/{id}/. Segue os redirects
// de fato e extrai o appid da URL final.
export async function resolveSteamAppId(offerUrl: string): Promise<string | null> {
  try {
    const res = await fetch(offerUrl, { redirect: 'follow' });
    return extractSteamAppId(res.url);
  } catch {
    return null;
  }
}

// Fallback quando a Steam não dá pra classificar (ex: oferta aponta pra um
// bundle, que a appdetails não aceita) — evita ficar tentando pra sempre.
const DLC_TITLE_PATTERNS = [
  /\bDLC\b/i,
  /\bSeason Pass\b/i,
  /\bSoundtrack\b/i,
  /\bOST\b/i,
  /\bArt Book\b/i,
  /\bSkin Set\b/i,
  /\bSkin Pack\b/i,
  /\bBooster Pack\b/i,
  /\bExpansion\b/i,
  /\bAdd-on\b/i,
];

export function titleLooksLikeDlc(title: string): boolean {
  return DLC_TITLE_PATTERNS.some(p => p.test(title));
}

export interface SteamAppDetails {
  isDlc: boolean;
  headerImage: string | null;
}

export async function fetchSteamAppDetails(appid: string): Promise<SteamAppDetails | null> {
  try {
    const res = await fetch(`https://store.steampowered.com/api/appdetails?appids=${appid}&l=portuguese`);
    if (!res.ok) return null;
    const data = await res.json();
    const entry = data?.[appid];
    if (!entry?.success || !entry.data) return null;
    return {
      isDlc: entry.data.type === 'dlc',
      headerImage: entry.data.header_image ?? null,
    };
  } catch {
    return null;
  }
}
