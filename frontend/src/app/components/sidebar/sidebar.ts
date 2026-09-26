import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { MenuMobileService } from '../../services/menu-mobile';
import { FavoritesService } from '../../services/favorites';

@Component({
  selector: 'app-sidebar',
  standalone: false,
  templateUrl: './sidebar.html',
  styleUrl: './sidebar.scss',
})
export class Sidebar implements OnInit, OnDestroy {
  /**
   * Liga por um instante quando um jogo novo entra nos monitorados, pra o item do menu dar um
   * destaque e mostrar pra onde o jogo foi (26/09/2026).
   */
  destacarMonitorados = false;
  private sub?: Subscription;
  private timer?: ReturnType<typeof setTimeout>;

  constructor(
    public menuMobile: MenuMobileService,
    public router: Router,
    private favoritesService: FavoritesService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    this.sub = this.favoritesService.adicionado$.subscribe(() => {
      clearTimeout(this.timer);
      // Desliga e religa no quadro seguinte: dois jogos seguidos reiniciam a animacao.
      this.destacarMonitorados = false;
      this.cdr.markForCheck();
      requestAnimationFrame(() => {
        this.destacarMonitorados = true;
        this.cdr.markForCheck();
        this.timer = setTimeout(() => { this.destacarMonitorados = false; this.cdr.markForCheck(); }, 1200);
      });
    });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
    clearTimeout(this.timer);
  }
}
