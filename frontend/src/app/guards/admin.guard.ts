import { inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../services/auth';
import { supabase } from '../services/supabase';
import { URL_API } from '../configuracao/url-api';

/**
 * Libera /admin so pra quem o backend reconhece como admin (ADMIN_USER_IDS). Pergunta ao backend em
 * vez de comparar com um UID fixo no codigo: o repositorio e publico, e a regra de verdade ja mora
 * no servidor (toda rota /api/admin responde 403 pra quem nao e admin). Este guard so evita abrir
 * a pagina vazia.
 */
export const adminGuard = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const http = inject(HttpClient);
  await auth.aguardarSessaoInicial();
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  if (!token) return router.createUrlTree(['/']);
  try {
    await firstValueFrom(http.get(`${URL_API}/admin/acesso`, { headers: { Authorization: `Bearer ${token}` } }));
    return true;
  } catch {
    return router.createUrlTree(['/']);
  }
};
