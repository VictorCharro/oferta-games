import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
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

  constructor(private gameService: GameService, private cdr: ChangeDetectorRef) {}

  ngOnInit() { this.loadPage(); }

  loadPage() {
    this.loading = true;
    this.gameService.getGames(this.page, this.pageSize).subscribe({
      next: (data) => {
        this.games = [...this.games, ...data];
        this.hasMore = data.length === this.pageSize;
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => { this.loading = false; this.cdr.detectChanges(); }
    });
  }

  loadMore() {
    this.page++;
    this.loadPage();
  }
}
