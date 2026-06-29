import { NgModule, provideBrowserGlobalErrorListeners } from '@angular/core';
import { BrowserModule } from '@angular/platform-browser';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

import { AppRoutingModule } from './app-routing-module';
import { App } from './app';
import { Sidebar } from './components/sidebar/sidebar';
import { Topbar } from './components/topbar/topbar';
import { GameCard } from './components/game-card/game-card';
import { Home } from './pages/home/home';
import { Catalog } from './pages/catalog/catalog';
import { Promotions } from './pages/promotions/promotions';
import { BestSellers } from './pages/best-sellers/best-sellers';
import { GameDetail } from './pages/game-detail/game-detail';
import { Search } from './pages/search/search';
import { FreeGames } from './pages/free-games/free-games';

@NgModule({
  declarations: [
    App,
    Sidebar,
    Topbar,
    GameCard,
    Home,
    Catalog,
    Promotions,
    BestSellers,
    GameDetail,
    Search,
    FreeGames,
  ],
  imports: [BrowserModule, AppRoutingModule, HttpClientModule, FormsModule],
  providers: [provideBrowserGlobalErrorListeners()],
  bootstrap: [App],
})
export class AppModule {}
