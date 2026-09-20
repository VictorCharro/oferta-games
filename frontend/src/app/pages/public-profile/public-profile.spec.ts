import { PublicProfile } from './public-profile';

describe('listas derivadas do perfil', () => {
  it('reutiliza os platinados e recalcula quando a biblioteca e substituida', () => {
    const perfil = Object.create(PublicProfile.prototype) as PublicProfile;
    const jogo = { appId: 1, minutosJogadas: 60, conquistasTotal: 5, conquistasDesbloqueadas: 5 };
    perfil.profile = { biblioteca: [jogo] } as any;
    const primeira = perfil.platinumGames;
    expect(perfil.platinumGames).toBe(primeira);
    perfil.profile!.biblioteca = [];
    expect(perfil.platinumGames).toEqual([]);
    expect(perfil.platinumGames).not.toBe(primeira);
  });
});
