import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { Home } from './pages/home/home';
import { Catalog } from './pages/catalog/catalog';
import { BestSellers } from './pages/best-sellers/best-sellers';
import { GameDetail } from './pages/game-detail/game-detail';
import { Search } from './pages/search/search';
import { FreeGames } from './pages/free-games/free-games';
import { Login } from './pages/login/login';
import { Profile } from './pages/profile/profile';
import { Settings } from './pages/settings/settings';
import { Favorites } from './pages/favorites/favorites';
import { AdminColeta } from './pages/admin-coleta/admin-coleta';
import { PublicProfile } from './pages/public-profile/public-profile';
import { NotFound } from './pages/not-found/not-found';
import { authGuard } from './guards/auth.guard';
import { adminGuard } from './guards/admin.guard';

const routes: Routes = [
  { path: '', component: Home },
  { path: 'catalogo', component: Catalog },
  { path: 'promocoes', redirectTo: 'catalogo', pathMatch: 'full' },
  { path: 'mais-vendidos', component: BestSellers },
  { path: 'jogo/:slug', component: GameDetail },
  { path: 'busca', component: Search },
  { path: 'gratuitos', component: FreeGames },
  { path: 'login', component: Login },
  { path: 'perfil', canActivate: [authGuard], component: Profile },
  { path: 'configuracoes', canActivate: [authGuard], component: Settings },
  { path: 'monitorados', canActivate: [authGuard], component: Favorites },
  { path: 'admin/coleta', canActivate: [adminGuard], component: AdminColeta },
  { path: 'favoritos', redirectTo: 'monitorados', pathMatch: 'full' },
  { path: ':handle', component: PublicProfile },
  { path: '**', component: NotFound },
];

@NgModule({
  imports: [RouterModule.forRoot(routes, { scrollPositionRestoration: 'top' })],
  exports: [RouterModule]
})
export class AppRoutingModule { }
