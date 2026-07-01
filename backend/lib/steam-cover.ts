export function steamCoverFromUrl(url: string): string | null {
  const match = url.match(/store\.steampowered\.com\/app\/(\d+)/);
  return match ? `https://cdn.akamai.steamstatic.com/steam/apps/${match[1]}/header.jpg` : null;
}
