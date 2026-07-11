import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth';

const UID_ADMINISTRADOR = '0a6eb06b-756e-4434-899b-33420bed8609';

export const adminGuard = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  await auth.aguardarSessaoInicial();
  return auth.user?.id === UID_ADMINISTRADOR || router.createUrlTree(['/']);
};
