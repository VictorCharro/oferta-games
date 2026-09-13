import { inject } from '@angular/core';
import { ActivatedRouteSnapshot, Router, RouterStateSnapshot } from '@angular/router';
import { AuthService } from '../services/auth';

export const authGuard = (_rota: ActivatedRouteSnapshot, estado: RouterStateSnapshot) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isLoggedIn) return true;
  // Volta pra pagina pedida depois de entrar (ex.: link de /monitorados aberto deslogado).
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: estado.url } });
};
