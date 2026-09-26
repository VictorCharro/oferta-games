import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth';
import { AcessoAdminService } from '../services/acesso-admin';

/**
 * Libera /admin so pra quem o backend reconhece como admin (ADMIN_USER_IDS). Pergunta ao backend em
 * vez de comparar com um UID fixo no codigo: o repositorio e publico, e a regra de verdade ja mora
 * no servidor (toda rota /api/admin responde 403 pra quem nao e admin). Este guard so evita abrir
 * a pagina vazia. A pergunta e a mesma do atalho do menu da conta (AcessoAdminService).
 */
export const adminGuard = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const acesso = inject(AcessoAdminService);
  await auth.aguardarSessaoInicial();
  return (await acesso.ehAdmin()) ? true : router.createUrlTree(['/']);
};
