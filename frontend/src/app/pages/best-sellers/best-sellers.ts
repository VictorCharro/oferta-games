import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
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
  error = false;

  constructor(private gameService: GameService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.load();
  }

  load() {
    this.loading = true;
    this.error = false;
    this.gameService.getGames(0, 40).subscribe({
      next: (data) => { this.games = data; this.loading = false; this.cdr.detectChanges(); },
      error: () => { this.loading = false; this.error = true; this.cdr.detectChanges(); }
    });
  }
}
