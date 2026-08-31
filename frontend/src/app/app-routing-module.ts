import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { Home } from './pages/home/home';
import { Catalog } from './pages/catalog/catalog';
import { BestSellers } from './pages/best-sellers/best-sellers';
import { Search } from './pages/search/search';
import { FreeGames } from './pages/free-games/free-games';
import { Favorites } from './pages/favorites/favorites';
import { NotFound } from './pages/not-found/not-found';
import { authGuard } from './guards/auth.guard';
import { adminGuard } from './guards/admin.guard';

// As paginas mais pesadas usam loadComponent pra sair do bundle inicial: antes todo visitante
// baixava as 14 paginas so pra abrir a Home, incluindo /admin/coleta (usavel por um unico UID).
// As leves (Home, Catalogo, Busca...) continuam eager porque sao o caminho comum de entrada e
// separa-las custaria uma requisicao a mais sem economia relevante.
const routes: Routes = [
  { path: '', component: Home },
  { path: 'catalogo', component: Catalog },
  { path: 'promocoes', redirectTo: 'catalogo', pathMatch: 'full' },
  { path: 'mais-vendidos', component: BestSellers },
  { path: 'jogo/:slug', loadComponent: () => import('./pages/game-detail/game-detail').then(m => m.GameDetail) },
  { path: 'busca', component: Search },
  { path: 'gratuitos', component: FreeGames },
  { path: 'login', loadComponent: () => import('./pages/login/login').then(m => m.Login) },
  { path: 'perfil', canActivate: [authGuard], loadComponent: () => import('./pages/profile/profile').then(m => m.Profile) },
  { path: 'configuracoes', canActivate: [authGuard], loadComponent: () => import('./pages/settings/settings').then(m => m.Settings) },
  { path: 'monitorados', canActivate: [authGuard], component: Favorites },
  { path: 'admin/coleta', canActivate: [adminGuard], loadComponent: () => import('./pages/admin-coleta/admin-coleta').then(m => m.AdminColeta) },
  { path: 'favoritos', redirectTo: 'monitorados', pathMatch: 'full' },
  { path: ':handle', loadComponent: () => import('./pages/public-profile/public-profile').then(m => m.PublicProfile) },
  { path: '**', component: NotFound },
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { scrollPositionRestoration: 'top' })],
  exports: [RouterModule]
})
export class AppRoutingModule { }
