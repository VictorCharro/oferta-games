import { Component, OnInit } from '@angular/core';
import { GameService, GameSummary } from '../../services/game';

@Component({
  selector: 'app-catalog',
  standalone: false,
  templateUrl: './catalog.html',
  styleUrl: './catalog.scss',
})
export class Catalog implements OnInit {
  games: GameSummary[] = [];
  loading = true;
  page = 0;
  hasMore = true;
  readonly pageSize = 20;

  constructor(private gameService: GameService) {}

  ngOnInit() { this.loadPage(); }

  loadPage() {
    this.loading = true;
    this.gameService.getGames(this.page, this.pageSize).subscribe({
      next: (data) => {
        this.games = [...this.games, ...data];
        this.hasMore = data.length === this.pageSize;
        this.loading = false;
      },
      error: () => { this.loading = false; }
    });
  }

  loadMore() {
    this.page++;
    this.loadPage();
  }
}
