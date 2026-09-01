import { Component, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { Subscription } from 'rxjs';
import { FavoritesService, FavoriteGame } from '../../services/favorites';

@Component({
  selector: 'app-favorites',
  standalone: false,
  templateUrl: './favorites.html',
  styleUrl: './favorites.scss',
})
export class Favorites implements OnInit, OnDestroy {
  games: FavoriteGame[] = [];
  loading = true;
  private sub!: Subscription;
  private carregadoSub!: Subscription;

  constructor(private favoritesService: FavoritesService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.sub = this.favoritesService.list$.subscribe(list => {
      this.games = list;
      this.cdr.detectChanges();
    });
    // `loading` segue o carregado$ do servico, e nao a primeira emissao de list$: essa emissao e o
    // valor inicial do BehaviorSubject (lista vazia), nao uma resposta do servidor. Usa-la aqui
    // fazia a tela piscar "nenhum jogo monitorado" antes dos jogos aparecerem.
    this.carregadoSub = this.favoritesService.carregado$.subscribe(carregado => {
      this.loading = !carregado;
      this.cdr.detectChanges();
    });
    this.favoritesService.load();
  }

  ngOnDestroy() {
    this.sub.unsubscribe();
    this.carregadoSub?.unsubscribe();
  }
}
