export interface StoreBrand {
  name: string;
  logo: string;
}

export interface PlatformBrand {
  name: string;
  logo: string;
}

const DEFAULT_BRAND: StoreBrand = {
  name: 'Loja',
  logo: 'favicon-32.png',
};

const PC_PLATFORM: PlatformBrand = { name: 'PC', logo: 'platform-logos/pc.svg' };
const XBOX_PLATFORM: PlatformBrand = { name: 'Xbox', logo: 'store-logos/xbox.svg' };
const PLAYSTATION_PLATFORM: PlatformBrand = { name: 'PlayStation', logo: 'platform-logos/playstation.svg' };

const BRANDS: Array<{ pattern: RegExp; brand: StoreBrand }> = [
  { pattern: /steam/i, brand: { name: 'Steam', logo: 'store-logos/steam.svg' } },
  { pattern: /microsoft/i, brand: { name: 'Microsoft Store', logo: 'store-logos/microsoft.svg' } },
  { pattern: /xbox/i, brand: { name: 'Xbox', logo: 'store-logos/xbox.svg' } },
  { pattern: /playstation|\bpsn\b/i, brand: { name: 'PlayStation Store', logo: 'platform-logos/playstation.svg' } },
  { pattern: /epic/i, brand: { name: 'Epic Games Store', logo: 'store-logos/epic.svg' } },
  { pattern: /\bgog\b/i, brand: { name: 'GOG', logo: 'store-logos/gog.svg' } },
  { pattern: /ubisoft/i, brand: { name: 'Ubisoft Store', logo: 'store-logos/ubisoft.svg' } },
  { pattern: /\bea\b|electronic arts/i, brand: { name: 'EA Store', logo: 'store-logos/ea.svg' } },
  { pattern: /blizzard|battle\.?net/i, brand: { name: 'Battle.net', logo: 'store-logos/battle-net.svg' } },
  { pattern: /humble/i, brand: { name: 'Humble Store', logo: 'store-logos/humble.svg' } },
  { pattern: /fanatical/i, brand: { name: 'Fanatical', logo: 'store-logos/fanatical.svg' } },
  { pattern: /nuuvem/i, brand: { name: 'Nuuvem', logo: 'store-logos/nuuvem.svg' } },
  { pattern: /green.?man/i, brand: { name: 'Green Man Gaming', logo: 'store-logos/greenman.svg' } },
  { pattern: /gamers.?gate/i, brand: { name: 'GamersGate', logo: 'store-logos/gamersgate.svg' } },
  { pattern: /indie.?gala/i, brand: { name: 'IndieGala', logo: 'store-logos/indiegala.svg' } },
  { pattern: /2game/i, brand: { name: '2Game', logo: 'store-logos/twogame.svg' } },
  { pattern: /instant.?gaming/i, brand: { name: 'Instant Gaming', logo: 'store-logos/instant-gaming.png' } },
];

// Chaves espelham LojasCatalogo.java no backend (usadas no filtro "lojas preferidas" das
// configuracoes). Conferida contra as lojas com ofertas de fato ativas em producao, exceto as ja
// excluidas globalmente por LojasBloqueadas (GOG, Green Man Gaming, Humble etc.) e a Microsoft
// Store (ja coberta pelo filtro de Plataforma/Xbox).
export const STORE_FILTER_OPTIONS: Array<{ key: string; label: string }> = [
  { key: 'steam', label: 'Steam' },
  { key: 'epic', label: 'Epic Games Store' },
  { key: 'ubisoft', label: 'Ubisoft Store' },
  { key: 'ea', label: 'EA Store' },
  { key: 'battlenet', label: 'Battle.net' },
  { key: 'fanatical', label: 'Fanatical' },
  { key: 'nuuvem', label: 'Nuuvem' },
  { key: 'instantgaming', label: 'Instant Gaming' },
  { key: '2game', label: '2Game' },
  { key: 'indiegala', label: 'IndieGala' },
  { key: 'gamersgate', label: 'GamersGate' },
  { key: 'gamebillet', label: 'GameBillet' },
  { key: 'playsum', label: 'Playsum' },
  { key: 'dreamgame', label: 'Dreamgame' },
  { key: 'zapagames', label: 'Zapagames' },
  { key: 'gamesload', label: 'Gamesload' },
  { key: 'zoomplatform', label: 'ZOOM Platform' },
  { key: 'fortunadigital', label: 'Fortuna Digital' },
  { key: 'fireflower', label: 'FireFlower' },
  { key: 'etailmarket', label: 'eTail.Market' },
];

export function storeBrand(storeName?: string | null): StoreBrand {
  if (!storeName) return DEFAULT_BRAND;
  return BRANDS.find(({ pattern }) => pattern.test(storeName))?.brand ?? {
    name: storeName,
    logo: DEFAULT_BRAND.logo,
  };
}

export function storePlatforms(storeName?: string | null, url?: string | null): PlatformBrand[] {
  const source = `${storeName ?? ''} ${url ?? ''}`;
  if (!source.trim()) return [PC_PLATFORM];

  if (/playstation|\bpsn\b|store\.playstation\.com/i.test(source)) {
    return [PLAYSTATION_PLATFORM];
  }

  if (/xbox|xbox\.com/i.test(source)) {
    return [XBOX_PLATFORM];
  }

  if (/microsoft/i.test(source)) {
    return [XBOX_PLATFORM, PC_PLATFORM];
  }

  return [PC_PLATFORM];
}
