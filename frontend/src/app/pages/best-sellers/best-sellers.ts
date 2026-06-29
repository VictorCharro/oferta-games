import { Component, OnInit } from '@angular/core';
import { GameService, GameSummary } from '../../services/game';

@Component({
  selector: 'app-best-sellers',
  standalone: false,
  templateUrl: './best-sellers.html',
  styleUrl: './best-sellers.scss',
})
export class BestSellers implements OnInit {
  games: GameSummary[] = [];
  loading = true;

  constructor(private gameService: GameService) {}

  ngOnInit() {
    this.gameService.getGames(0, 40).subscribe({
      next: (data) => { this.games = data; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }
}
