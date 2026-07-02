export function extractSteamAppId(url: string): string | null {
  const match = url.match(/store\.steampowered\.com\/app\/(\d+)/);
  return match ? match[1] : null;
}

export function steamCoverFromUrl(url: string): string | null {
  const appid = extractSteamAppId(url);
  return appid ? `https://cdn.akamai.steamstatic.com/steam/apps/${appid}/header.jpg` : null;
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
