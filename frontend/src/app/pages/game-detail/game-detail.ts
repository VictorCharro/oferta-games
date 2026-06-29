import { Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { GameService, GameDetail as GameDetailModel, Offer } from '../../services/game';

@Component({
  selector: 'app-game-detail',
  standalone: false,
  templateUrl: './game-detail.html',
  styleUrl: './game-detail.scss',
})
export class GameDetail implements OnInit {
  game: GameDetailModel | null = null;
  loading = true;
  refreshing = false;
  refreshMsg = '';

  constructor(private route: ActivatedRoute, private gameService: GameService) {}

  ngOnInit() {
    const slug = this.route.snapshot.paramMap.get('slug')!;
    this.gameService.getGame(slug).subscribe({
      next: (data) => { this.game = data; this.loading = false; },
      error: () => { this.loading = false; }
    });
  }

  refresh() {
    if (!this.game || this.refreshing) return;
    this.refreshing = true;
    this.refreshMsg = '';
    this.gameService.refreshGame(this.game.slug).subscribe({
      next: (res) => {
        this.refreshMsg = `${res.updated} ofertas atualizadas`;
        this.refreshing = false;
        this.gameService.getGame(this.game!.slug).subscribe(d => this.game = d);
      },
      error: () => { this.refreshMsg = 'Erro ao atualizar'; this.refreshing = false; }
    });
  }

  bestPrice(offers: Offer[]): number | null {
    if (!offers.length) return null;
    return Math.min(...offers.map(o => o.price));
  }

  discount(offer: Offer): number {
    if (!offer.regularPrice) return 0;
    return Math.round((1 - offer.price / offer.regularPrice) * 100);
  }

  formatPrice(price: number | string | null): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return '—';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }
}
