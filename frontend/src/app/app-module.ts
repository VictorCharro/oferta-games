import { NgModule, provideBrowserGlobalErrorListeners } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { CommonModule } from '@angular/common';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

import { AppRoutingModule } from './app-routing-module';
import { App } from './app';
import { Sidebar } from './components/sidebar/sidebar';
import { Topbar } from './components/topbar/topbar';
import { GameCard } from './components/game-card/game-card';
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

@NgModule({
  declarations: [
    App,
    Sidebar,
    Topbar,
    GameCard,
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
  ],
  imports: [BrowserModule, CommonModule, AppRoutingModule, HttpClientModule, FormsModule],
  providers: [provideBrowserGlobalErrorListeners()],
  bootstrap: [App],
})
export class AppModule {}
