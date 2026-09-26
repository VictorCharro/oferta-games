import { Component, Input, OnInit, OnDestroy, ChangeDetectorRef } from '@angular/core';
import { Router, RouterModule } from '@angular/router';
import { CommonModule } from '@angular/common';
import { Subscription } from 'rxjs';
import { GameSummary } from '../../services/game';
import { resolveDlc } from '../../services/filters';
import { FavoritesService } from '../../services/favorites';
import { AuthService } from '../../services/auth';
import { PlatformBrand, storeBrand, storePlatforms } from '../../services/store-brand';
import { irParaLogin } from '../../services/ir-para-login';
import { trocarPorCapaPadrao } from '../../services/capa';
import { FadeImagem } from '../../diretivas/fade-imagem';
import { AvisosService } from '../../services/avisos';
import { mensagemMeta } from '../../services/mensagem-monitoramento';

/**
 * Card de oferta com monitoramento confirmado pelo servidor.
 *
 * Feedback do botao de monitorar (26/09/2026): enquanto o servidor nao responde, o botao pulsa
 * (`salvando`); ao confirmar, da um "pop" (`confirmado`) e um aviso diz o que aconteceu. Antes nao
 * havia nada entre o clique e a resposta, e o erro aparecia como texto dentro do card, empurrando o
 * layout.
 */
@Component({
  selector: 'app-game-card',
  imports: [CommonModule, RouterModule, FadeImagem],
  templateUrl: './game-card.html',
  styleUrl: './game-card.scss',
})
export class GameCard implements OnInit, OnDestroy {
  salvandoMonitoramento = false;
  /** Liga por um instante depois de confirmar, so pra disparar a animacao de "pop" do botao. */
  confirmado = false;
  private timerConfirmado?: ReturnType<typeof setTimeout>;
  /** Capa que nao carregou vira a imagem padrao (ver services/capa). */
  readonly capaIndisponivel = trocarPorCapaPadrao;

  @Input() game!: GameSummary;
  @Input() discountPct?: number;
  @Input() storeName?: string;
  @Input() storeUrl?: string | null;

  private sub!: Subscription;

  constructor(
    private favoritesService: FavoritesService,
    private auth: AuthService,
    private router: Router,
    private cdr: ChangeDetectorRef,
    private avisos: AvisosService
  ) {}

  ngOnInit() {
    this.sub = this.favoritesService.slugs$.subscribe(() => this.cdr.detectChanges());
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
    clearTimeout(this.timerConfirmado);
  }

  get monitoring(): boolean {
    return this.favoritesService.isFavorited(this.game?.slug);
  }

  get discount(): number {
    if (this.discountPct != null) return this.discountPct;
    const min = Number(this.game?.minPrice);
    const reg = Number(this.game?.regularPrice);
    if (!reg || !min || reg <= min) return 0;
    return Math.round((1 - min / reg) * 100);
  }

  get isDlcGame(): boolean {
    return resolveDlc(this.game?.title ?? '', this.game?.isDlc);
  }

  get displayStoreName(): string | null {
    return this.storeName ?? this.game?.storeName ?? null;
  }

  get displayStoreUrl(): string | null {
    return this.storeUrl ?? this.game?.url ?? null;
  }

  formatPrice(price: number | string | null): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return '—';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  storeLogo(storeName?: string | null): string {
    return storeBrand(storeName).logo;
  }

  platforms(storeName?: string | null): PlatformBrand[] {
    return storePlatforms(storeName, this.displayStoreUrl);
  }

  async toggleMonitoring(event: Event) {
    event.preventDefault();
    event.stopPropagation();
    if (!this.auth.isLoggedIn) {
      irParaLogin(this.router, 'monitorar este jogo');
      return;
    }
    if (this.salvandoMonitoramento) return;
    const estavaMonitorando = this.monitoring;
    this.salvandoMonitoramento = true;
    this.cdr.markForCheck();
    try {
      await this.favoritesService.toggle(this.game);
      this.animarConfirmacao();
      // O aviso diz o que muda pra pessoa (o alerta de preco), nao so "salvo".
      if (estavaMonitorando) this.avisos.info(`${this.game.title} não está mais sendo monitorado.`);
      else this.avisos.sucesso(mensagemMeta(this.game.title, false, null));
    } catch {
      this.avisos.erro('Não foi possível alterar o monitoramento. Tente novamente.');
    } finally {
      this.salvandoMonitoramento = false;
      this.cdr.markForCheck();
    }
  }

  private animarConfirmacao() {
    clearTimeout(this.timerConfirmado);
    this.confirmado = true;
    // Tempo da animacao monitor-pop em game-card.scss (--dur-lento) com folga.
    this.timerConfirmado = setTimeout(() => { this.confirmado = false; this.cdr.markForCheck(); }, 450);
  }
}
