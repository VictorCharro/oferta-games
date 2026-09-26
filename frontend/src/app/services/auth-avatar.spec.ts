import { describe, expect, it } from 'vitest';
import { BehaviorSubject } from 'rxjs';
import { AuthService, CHAVE_AVATAR_PERSONALIZADO } from './auth';

/** AuthService sem construtor (padrao do projeto): so o necessario pro loadAvatar. */
function criar(avatarDoPerfil: string | null = null) {
  const auth = Object.create(AuthService.prototype) as AuthService;
  Object.assign(auth, {
    _user: new BehaviorSubject(null),
    _avatar: new BehaviorSubject(''),
    avatarConferido: new Set<string>(),
    perfis: { proprio: async () => ({ avatarUrl: avatarDoPerfil }) },
  });
  return auth;
}

const carregar = (auth: AuthService, user: unknown) => (auth as any).loadAvatar(user);

describe('AuthService.loadAvatar', () => {
  // O bug: login pelo Google regrava avatar_url e a foto enviada pelo usuario sumia.
  it('foto personalizada vence a do Google', () => {
    const auth = criar();
    carregar(auth, { id: 'u1', user_metadata: { avatar_url: 'https://google/foto.png', [CHAVE_AVATAR_PERSONALIZADO]: 'https://nosso/avatars/u1/avatar' } });
    expect(auth.avatarUrl).toBe('https://nosso/avatars/u1/avatar');
  });

  it('sem foto enviada usa a do provedor', () => {
    const auth = criar();
    carregar(auth, { id: 'u1', user_metadata: { avatar_url: 'https://google/foto.png' } });
    expect(auth.avatarUrl).toBe('https://google/foto.png');
  });

  it('deslogado fica sem foto', () => {
    const auth = criar();
    carregar(auth, null);
    expect(auth.avatarUrl).toBe('');
  });
});
