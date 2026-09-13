import { ChangeDetectorRef, Component, ElementRef, HostListener, Input, OnDestroy } from '@angular/core';
import { Router } from '@angular/router';
import { FavoriteGame, FavoritesService } from '../../services/favorites';
import { AuthService } from '../../services/auth';
import { storeBrand } from '../../services/store-brand';
import { irParaLogin } from '../../services/ir-para-login';

@Component({
  selector: 'app-monitored-game-card',
  standalone: false,
  templateUrl: './monitored-game-card.html',
  styleUrl: './monitored-game-card.scss',
})
export class MonitoredGameCard implements OnDestroy {
  @Input({ required: true }) game!: FavoriteGame;

  menuMetaAberto = false;
  metaPrecoInput = '';
  salvandoMeta = false;
  // Posicao calculada em pixels de viewport (position: fixed) - o card fica dentro de uma
  // faixa com scroll horizontal (`.monitor-row`, overflow-x: auto), que por causa da regra do
  // CSS de so poder cortar 1 eixo acaba virando overflow-y: auto tambem e cortaria um popover
  // absoluto normal. Fixed escapa desse corte, mas exige recalcular a posicao na mao.
  metaMenuPos = { top: 0, left: 0 };

  private botaoRef: HTMLElement | null = null;
  private rafId: number | null = null;

  constructor(
    private favoritesService: FavoritesService,
    private auth: AuthService,
    private router: Router,
    private el: ElementRef<HTMLElement>,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnDestroy() {
    this.pararMonitoramentoDePosicao();
  }

  formatPrice(price: number | string | null): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return '—';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  storeLogo(storeName?: string | null): string {
    return storeBrand(storeName).logo;
  }

  private precoAtual(): number {
    return Number(this.game.minPrice) || 0;
  }

  get metaAtingida(): boolean {
    return this.game.targetPrice != null && this.precoAtual() <= Number(this.game.targetPrice);
  }

  get diferencaMeta(): number {
    return this.precoAtual() - Number(this.game.targetPrice ?? 0);
  }

  get progressoPct(): number {
    if (this.game.targetPrice == null) return 0;
    const atual = this.precoAtual();
    if (atual <= 0) return 100;
    return Math.max(0, Math.min(100, Math.round((Number(this.game.targetPrice) / atual) * 100)));
  }

  abrirMenuMeta(event: Event) {
    event.preventDefault();
    event.stopPropagation();
    if (!this.auth.isLoggedIn) {
      irParaLogin(this.router, 'definir uma meta de preço');
      return;
    }
    // Ancora sempre no elemento clicado (o icone ou o link "definir meta"), colado na borda
    // esquerda dele - so desliza pra esquerda se fosse vazar a borda direita da tela.
    this.botaoRef = event.currentTarget as HTMLElement;
    this.posicionarMenu();
    this.metaPrecoInput = this.game.targetPrice != null ? String(this.game.targetPrice) : '';
    this.menuMetaAberto = true;
    this.monitorarPosicaoParaFechar();
    this.cdr.detectChanges();
  }

  private posicionarMenu() {
    if (!this.botaoRef) return;
    const rect = this.botaoRef.getBoundingClientRect();
    const largura = 220;
    this.metaMenuPos = {
      top: rect.bottom + 6,
      left: Math.max(8, Math.min(rect.left, window.innerWidth - largura - 8)),
    };
  }

  // O popover e position:fixed, calculado a partir do botao no momento do clique - se a pagina
  // ou o carrossel rolar depois, essa posicao fica desatualizada. Em vez de depender de eventos
  // de scroll (que nao chegam de forma confiavel pra todo tipo de scroll/ancestral), compara a
  // posicao real do botao a cada frame e fecha o popover assim que ela mudar.
  private monitorarPosicaoParaFechar() {
    if (!this.botaoRef) return;
    const posicaoInicial = this.botaoRef.getBoundingClientRect();
    const verificar = () => {
      if (!this.menuMetaAberto || !this.botaoRef) {
        this.rafId = null;
        return;
      }
      const atual = this.botaoRef.getBoundingClientRect();
      if (atual.top !== posicaoInicial.top || atual.left !== posicaoInicial.left) {
        this.fecharMenuMeta();
        return;
      }
      this.rafId = requestAnimationFrame(verificar);
    };
    this.rafId = requestAnimationFrame(verificar);
  }

  private pararMonitoramentoDePosicao() {
    if (this.rafId != null) {
      cancelAnimationFrame(this.rafId);
      this.rafId = null;
    }
  }

  fecharMenuMeta() {
    this.menuMetaAberto = false;
    this.metaPrecoInput = '';
    this.botaoRef = null;
    this.pararMonitoramentoDePosicao();
    this.cdr.detectChanges();
  }

  async salvarMeta(event?: Event) {
    event?.preventDefault();
    event?.stopPropagation();
    if (this.salvandoMeta) return;
    const valor = this.metaPrecoInput.trim().replace(',', '.');
    const targetPrice = valor ? Number(valor) : null;
    if (valor && (isNaN(targetPrice!) || targetPrice! < 0)) return;

    this.salvandoMeta = true;
    this.cdr.detectChanges();
    try {
      await this.favoritesService.add(this.game.slug, targetPrice);
      this.fecharMenuMeta();
    } finally {
      this.salvandoMeta = false;
      this.cdr.detectChanges();
    }
  }

  async removerMonitoramento(event?: Event) {
    event?.preventDefault();
    event?.stopPropagation();
    if (this.salvandoMeta) return;
    this.salvandoMeta = true;
    this.cdr.detectChanges();
    try {
      await this.favoritesService.remove(this.game.slug);
      this.fecharMenuMeta();
    } finally {
      this.salvandoMeta = false;
      this.cdr.detectChanges();
    }
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    if (this.menuMetaAberto && !this.el.nativeElement.contains(event.target as Node) && !(event.target as HTMLElement).closest('.monitor-meta-menu')) {
      this.fecharMenuMeta();
    }
  }
}
