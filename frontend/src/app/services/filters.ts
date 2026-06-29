const NON_GAME_PATTERNS = [
  // DLCs e conteúdo adicional
  /\bDLC\b/i,
  /\bSeason Pass\b/i,
  /\bSoundtrack\b/i,
  /\bOST\b/i,
  /\bArt Book\b/i,
  /\bSkin Set\b/i,
  /\bSkin Pack\b/i,
  /\bCosmetic\b/i,
  /\bBooster Pack\b/i,
  / - .*(Pack|Bundle|Boost|Content|Expansion|Add-?on|Extra|Bonus|Upgrade|Digital Content)/i,
  // Guias e livros
  /\bDetonado\b/i,
  /\bGuide Book\b/i,
  /\bPrima Guide\b/i,
  /\bStrategy Guide\b/i,
  /\bMaking of\b/i,
];

export function isDlc(title: string): boolean {
  return NON_GAME_PATTERNS.some(p => p.test(title));
}
