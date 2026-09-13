import { Router } from '@angular/router';

/**
 * Manda pro login lembrando de onde a pessoa veio e por que (issue #28).
 *
 * Antes, clicar em "Favoritar" deslogado caia no /login sem explicacao e, depois de entrar, a pessoa
 * ia parar na Home — longe do jogo que queria favoritar. O login le `returnUrl` (validado contra
 * redirecionamento aberto, ver destinoSeguro) e mostra "Entre para {motivo}".
 *
 * @param motivo complemento de "Entre para ...", ex.: "favoritar este jogo"
 */
export function irParaLogin(router: Router, motivo?: string) {
  return router.navigate(['/login'], {
    queryParams: { returnUrl: router.url, ...(motivo ? { motivo } : {}) },
  });
}
