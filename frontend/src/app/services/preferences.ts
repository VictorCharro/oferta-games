import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

// localStorage nao existe em Node (SSR); sem essa guarda, so de injetar este servico no server ja
// derruba a renderizacao inteira com ReferenceError.
const isBrowser = typeof window !== 'undefined';

export type PreferredPlatform = 'all' | 'pc' | 'xbox';

export interface UserPreferences {
  preferredPlatform: PreferredPlatform;
  hideDlcs: boolean;
  minimumDiscount: number;
  maximumPrice: number | null;
}

export interface PrivacyPreferences {
  publicProfile: boolean;
  showGameHours: boolean;
  showAchievements: boolean;
  showLibrary: boolean;
  showFavoriteGames: boolean;
  showRecentActivity: boolean;
  showCollections: boolean;
}

const DEFAULT_PREFERENCES: UserPreferences = {
  preferredPlatform: 'all',
  hideDlcs: false,
  minimumDiscount: 0,
  maximumPrice: null,
};

const DEFAULT_PRIVACY: PrivacyPreferences = {
  publicProfile: true,
  showGameHours: true,
  showAchievements: true,
  showLibrary: true,
  showFavoriteGames: true,
  showRecentActivity: true,
  showCollections: true,
};

@Injectable({ providedIn: 'root' })
export class PreferencesService {
  private readonly preferencesKey = 'oferta-games-preferences';
  private readonly privacyKey = 'oferta-games-privacy';
  private readonly preferencesSubject = new BehaviorSubject<UserPreferences>(
    this.read(this.preferencesKey, DEFAULT_PREFERENCES)
  );
  private readonly privacySubject = new BehaviorSubject<PrivacyPreferences>(
    this.read(this.privacyKey, DEFAULT_PRIVACY)
  );

  readonly preferences$ = this.preferencesSubject.asObservable();
  readonly privacy$ = this.privacySubject.asObservable();

  get preferences(): UserPreferences { return this.preferencesSubject.value; }
  get privacy(): PrivacyPreferences { return this.privacySubject.value; }

  savePreferences(value: UserPreferences) {
    const normalized = {
      ...value,
      minimumDiscount: Math.min(100, Math.max(0, Number(value.minimumDiscount) || 0)),
      maximumPrice: value.maximumPrice == null || value.maximumPrice === 0
        ? null
        : Math.max(0, Number(value.maximumPrice)),
    };
    if (isBrowser) localStorage.setItem(this.preferencesKey, JSON.stringify(normalized));
    this.preferencesSubject.next(normalized);
  }

  savePrivacy(value: PrivacyPreferences) {
    if (isBrowser) localStorage.setItem(this.privacyKey, JSON.stringify(value));
    this.privacySubject.next({ ...value });
  }

  private read<T>(key: string, defaults: T): T {
    if (!isBrowser) return { ...defaults };
    try {
      const saved = localStorage.getItem(key);
      return saved ? { ...defaults, ...JSON.parse(saved) } : { ...defaults };
    } catch {
      return { ...defaults };
    }
  }
}
