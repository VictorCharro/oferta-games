import { NgModule, provideBrowserGlobalErrorListeners } from '@angular/core';
import { BrowserModule, provideClientHydration, withEventReplay } from '@angular/platform-browser';
import { CommonModule } from '@angular/common';
import { provideHttpClient, withFetch } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { DragDropModule } from '@angular/cdk/drag-drop';

import { AppRoutingModule } from './app-routing-module';
import { App } from './app';
import { Sidebar } from './components/sidebar/sidebar';
import { Topbar } from './components/topbar/topbar';
import { GameCard } from './components/game-card/game-card';
import { DealsCarousel } from './components/deals-carousel/deals-carousel';
import { LoadError } from './components/load-error/load-error';
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

@NgModule({
  declarations: [
    App,
    Sidebar,
    Topbar,
    GameCard,
    DealsCarousel,
    LoadError,
    Home,
    Catalog,
    BestSellers,
    GameDetail,
    Search,
    FreeGames,
    Login,
    Profile,
    Settings,
    Favorites,
    AdminColeta,
    PublicProfile,
    NotFound,
  ],
  imports: [BrowserModule, CommonModule, AppRoutingModule, FormsModule, DragDropModule],
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideClientHydration(withEventReplay()),
    provideHttpClient(withFetch()),
  ],
  bootstrap: [App],
})
export class AppModule {}
