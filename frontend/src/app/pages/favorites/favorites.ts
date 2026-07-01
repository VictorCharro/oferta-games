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

  constructor(private favoritesService: FavoritesService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.sub = this.favoritesService.list$.subscribe(list => {
      this.games = list;
      this.loading = false;
      this.cdr.detectChanges();
    });
    this.favoritesService.load();
  }

  ngOnDestroy() {
    this.sub.unsubscribe();
  }
}
