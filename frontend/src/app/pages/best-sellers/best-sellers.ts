import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { GameService, GameSummary } from '../../services/game';
import { SeoService } from '../../services/seo';

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

  constructor(private gameService: GameService, private cdr: ChangeDetectorRef, private seo: SeoService) {}

  ngOnInit() {
    this.seo.set({
      title: 'Mais vendidos',
      description: 'Os jogos mais populares do momento, com os melhores preços encontrados nas principais lojas.',
      path: '/mais-vendidos',
    });
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
