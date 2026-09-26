import { ErrorHandler, LOCALE_ID, NgModule, provideBrowserGlobalErrorListeners } from '@angular/core';
import { RelatorErros } from './configuracao/relator-erros';
import { BrowserModule, provideClientHydration, withEventReplay } from '@angular/platform-browser';
import { CommonModule, registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
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
import { FadeImagem } from './diretivas/fade-imagem';
import { Avisos } from './components/avisos/avisos';

// Numeros e datas dos pipes (| number, | date) no formato brasileiro: "4.997" e nao "4,997", nota
// "4,5" e nao "4.5". Sem isto o Angular usa en-US, e isso aparecia na pagina do jogo (contagem de
// reviews) e no admin. Precos nao dependem disto: sao formatados a mao com toLocaleString('pt-BR').
// Fica aqui, e nao no main.ts, porque o AppServerModule importa este modulo: vale no SSR tambem.
registerLocaleData(localePt);

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
    GameCard, PriceHistoryChart, FadeImagem, Avisos],
  providers: [
    { provide: LOCALE_ID, useValue: 'pt-BR' },
    provideBrowserGlobalErrorListeners(),
    { provide: ErrorHandler, useClass: RelatorErros },
    provideClientHydration(withEventReplay()),
    provideHttpClient(withFetch(), withInterceptors([tokenSsrInterceptor])),
  ],
  bootstrap: [App],
})
export class AppModule {}
