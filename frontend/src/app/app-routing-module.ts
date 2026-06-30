import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { Home } from './pages/home/home';
import { Catalog } from './pages/catalog/catalog';
import { BestSellers } from './pages/best-sellers/best-sellers';
import { GameDetail } from './pages/game-detail/game-detail';
import { Search } from './pages/search/search';
import { FreeGames } from './pages/free-games/free-games';
import { Login } from './pages/login/login';
import { authGuard } from './guards/auth.guard';

const routes: Routes = [
  { path: '', component: Home },
  { path: 'catalogo', component: Catalog },
  { path: 'promocoes', redirectTo: 'catalogo', pathMatch: 'full' },
  { path: 'mais-vendidos', component: BestSellers },
  { path: 'jogo/:slug', component: GameDetail },
  { path: 'busca', component: Search },
  { path: 'gratuitos', component: FreeGames },
  { path: 'login', component: Login },
  { path: 'favoritos', canActivate: [authGuard], component: Home },
  { path: '**', redirectTo: '' },
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule { }
