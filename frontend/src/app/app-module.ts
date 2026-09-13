import { NgModule, provideBrowserGlobalErrorListeners } from '@angular/core';
import { BrowserModule, provideClientHydration, withEventReplay } from '@angular/platform-browser';
import { CommonModule } from '@angular/common';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { tokenSsrInterceptor } from './configuracao/token-ssr';
import { FormsModule } from '@angular/forms';
import { DragDropModule } from '@angular/cdk/drag-drop';

import { AppRoutingModule } from './app-routing-module';
import { App } from './app';
import { Sidebar } from './components/sidebar/sidebar';
import { Topbar } from './components/topbar/topbar';
import { GameCard } from './components/game-card/game-card';
import { DealsCarousel } from './components/deals-carousel/deals-carousel';
import { LoadError } from './components/load-error/load-error';
import { PriceHistoryChart } from './components/price-history-chart/price-history-chart';
import { MonitoredDealsCarousel } from './components/monitored-deals-carousel/monitored-deals-carousel';
import { MonitoredGameCard } from './components/monitored-game-card/monitored-game-card';
import { Home } from './pages/home/home';
import { Catalog } from './pages/catalog/catalog';
import { BestSellers } from './pages/best-sellers/best-sellers';
import { Search } from './pages/search/search';
import { FreeGames } from './pages/free-games/free-games';
import { Favorites } from './pages/favorites/favorites';
import { NotFound } from './pages/not-found/not-found';

// As paginas pesadas nao aparecem aqui de proposito: sao standalone e entram por
// loadComponent nas rotas (ver app-routing-module). Declarar qualquer uma delas de volta faz
// ela voltar pro bundle inicial de todo visitante, desfazendo o lazy loading.
@NgModule({
  declarations: [
    App,
    Sidebar,
    Topbar,
    DealsCarousel,
    LoadError,
    MonitoredDealsCarousel,
    MonitoredGameCard,
    Home,
    Catalog,
    BestSellers,
    Search,
    FreeGames,
    Favorites,
    NotFound,
  ],
  // GameCard e PriceHistoryChart sao standalone (usados tanto pelas paginas lazy quanto pelas
  // declaradas aqui), entao entram como import, nao como declaration.
  imports: [BrowserModule, CommonModule, AppRoutingModule, FormsModule, DragDropModule,
    GameCard, PriceHistoryChart],
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideClientHydration(withEventReplay()),
    provideHttpClient(withFetch(), withInterceptors([tokenSsrInterceptor])),
  ],
  bootstrap: [App],
})
export class AppModule {}
