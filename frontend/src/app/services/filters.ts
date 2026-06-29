const DLC_PATTERNS = [
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
];

export function isDlc(title: string): boolean {
  return DLC_PATTERNS.some(p => p.test(title));
}
