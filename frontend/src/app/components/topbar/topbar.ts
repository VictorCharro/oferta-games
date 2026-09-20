import { Component, ElementRef, HostListener, ChangeDetectorRef, OnInit, OnDestroy, ViewChild } from '@angular/core';
import { Router, NavigationEnd } from '@angular/router';
import { Subject, Subscription, catchError, of } from 'rxjs';
import { debounceTime, distinctUntilChanged, filter, switchMap } from 'rxjs/operators';
import { ThemeService } from '../../services/theme';
import { MenuMobileService } from '../../services/menu-mobile';
import { AuthService } from '../../services/auth';
import { GameService, GameSummary } from '../../services/game';
import { SearchService } from '../../services/search';
import { PerfisService } from '../../services/perfis';
import { MinhaMensagem, NotificationsService, PriceNotification } from '../../services/notifications';

/** Navegacao global e sugestoes; falhas de uma consulta nao encerram a entrada de busca. */
@Component({
  selector: 'app-topbar',
  standalone: false,
  templateUrl: './topbar.html',
  styleUrl: './topbar.scss',
})
export class Topbar implements OnInit, OnDestroy {
  searchQuery = '';
  dropdownOpen = false;
  notificationsOpen = false;
  suggestions: GameSummary[] = [];
  notifications: PriceNotification[] = [];
  showSuggestions = false;
  // So tem efeito visual em mobile (ver @media em topbar.scss) — em desktop o campo de busca
  // sempre fica visivel, entao esse estado nunca chega a ser lido pelo CSS la.
  buscaMobileAberta = false;
  @ViewChild('searchInput') private searchInputRef?: ElementRef<HTMLInputElement>;
  private isCatalogPage = false;
  private sub!: Subscription;
  private avatarSub!: Subscription;
  private routeSub!: Subscription;
  private searchSub!: Subscription;
  private notificationsSub!: Subscription;
  private mensagensSub?: Subscription;
  private sessaoSub!: Subscription;
  private searchInput$ = new Subject<string>();

  constructor(
    public theme: ThemeService,
    public auth: AuthService,
    public menuMobile: MenuMobileService,
    private router: Router,
    private gameService: GameService,
    private searchService: SearchService,
    private perfisService: PerfisService,
    private notificationsService: NotificationsService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnInit() {
    this.sub = this.auth.user$.subscribe(() => this.cdr.detectChanges());
    // Sem isto o bloco de login fica escondido: o template depende de sessaoResolvida, e a virada
    // dela pode nao coincidir com uma emissao de user$ (quando ja estava null e continua null).
    this.sessaoSub = this.auth.sessaoResolvida$.subscribe(() => this.cdr.detectChanges());
    this.avatarSub = this.auth.avatar$.subscribe(() => this.cdr.detectChanges());
    this.notificationsSub = this.notificationsService.list$.subscribe(notifications => { this.notifications = notifications; this.cdr.detectChanges(); });
    this.mensagensSub = this.notificationsService.mensagens$.subscribe(() => this.cdr.detectChanges());

    this.isCatalogPage = this.router.url.startsWith('/catalogo');
    this.routeSub = this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(e => {
        // Fecha o drawer tambem em navegacao que nao veio de clicar num link da sidebar (voltar
        // do navegador, redirect programatico) — sem isso ele ficaria aberto sobre a pagina nova.
        this.menuMobile.close();
        this.buscaMobileAberta = false;
        this.isCatalogPage = e.urlAfterRedirects.startsWith('/catalogo');
        this.searchQuery = '';
        this.suggestions = [];
        this.showSuggestions = false;
        this.searchService.setQuery('');
        this.cdr.detectChanges();
      });

    this.searchSub = this.searchInput$
      .pipe(
        debounceTime(250),
        distinctUntilChanged(),
        switchMap(q => {
          const trimmed = q.trim();
          if (trimmed.length < 2) return of([]);
          // Recuperar dentro do switchMap mantem a inscricao para os proximos termos.
          return this.gameService.searchGames(trimmed).pipe(catchError(() => of([])));
        })
      )
      .subscribe(results => {
        this.suggestions = results.slice(0, 6);
        this.showSuggestions = this.suggestions.length > 0;
        this.cdr.detectChanges();
      });
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
    this.avatarSub?.unsubscribe();
    this.routeSub?.unsubscribe();
    this.searchSub?.unsubscribe();
    this.notificationsSub?.unsubscribe();
    this.mensagensSub?.unsubscribe();
    this.sessaoSub?.unsubscribe();
  }

  onSearchInput(value: string) {
    if (this.isCatalogPage) {
      this.suggestions = [];
      this.showSuggestions = false;
      this.searchService.setQuery(value);
      return;
    }
    if (!value.trim()) {
      this.suggestions = [];
      this.showSuggestions = false;
    }
    this.searchInput$.next(value);
  }

  onSearchFocus() {
    if (this.suggestions.length && !this.isCatalogPage) this.showSuggestions = true;
  }

  onSearch(event: KeyboardEvent) {
    if (event.key === 'Enter' && this.searchQuery.trim() && !this.isCatalogPage) {
      this.showSuggestions = false;
      this.router.navigate(['/busca'], { queryParams: { q: this.searchQuery.trim() } });
    } else if (event.key === 'Escape') {
      this.showSuggestions = false;
    }
  }

  /**
   * Abre a busca expandida em mobile (icone de lupa na topbar — ver design da issue de
   * responsividade). O campo de busca em si e o MESMO elemento sempre usado, so a visibilidade
   * muda via CSS; nao ha um segundo input duplicado.
   */
  abrirBuscaMobile() {
    this.buscaMobileAberta = true;
    this.cdr.detectChanges();
    // setTimeout(0): a troca de classe que torna o input visivel (display:none -> flex) precisa
    // renderizar antes do focus — focar um elemento ainda display:none e um no-op silencioso.
    setTimeout(() => this.searchInputRef?.nativeElement.focus());
  }

  fecharBuscaMobile() {
    this.buscaMobileAberta = false;
    this.showSuggestions = false;
  }

  closeSuggestions() {
    this.showSuggestions = false;
    this.searchQuery = '';
    this.suggestions = [];
  }

  formatPrice(price: number | string | null): string {
    const n = Number(price);
    if (price == null || isNaN(n)) return '—';
    return n.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
  }

  toggleDropdown() { this.dropdownOpen = !this.dropdownOpen; }
  toggleNotifications() {
    this.notificationsOpen = !this.notificationsOpen;
    // Busca respostas novas ao abrir o sino: sem isso so chegariam no proximo login/recarga.
    if (this.notificationsOpen) this.notificationsService.loadMensagens();
  }

  get unreadNotifications(): number { return this.notificationsService.unreadCount; }
  get respostasNaoLidas(): MinhaMensagem[] { return this.notificationsService.respostasNaoLidas; }

  abrirResposta() {
    this.notificationsOpen = false;
    this.router.navigate(['/contato'], { fragment: 'minhas' });
  }

  formatNotificationPrice(price: number): string { return Number(price).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' }); }

  relativeNotificationTime(value: string): string {
    const minutes = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 60000));
    if (minutes < 1) return 'agora';
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} h`;
    return `${Math.floor(hours / 24)} d`;
  }

  async markNotificationRead(notification: PriceNotification) {
    if (!notification.lida) await this.notificationsService.markRead(notification.id);
  }

  async markAllNotificationsRead() { await this.notificationsService.markAllRead(); }

  async removeNotification(event: MouseEvent, id: number) {
    event.preventDefault();
    event.stopPropagation();
    await this.notificationsService.remove(id);
  }

  async abrirPerfil() {
    this.dropdownOpen = false;
    try {
      const perfil = await this.perfisService.proprio();
      if (perfil?.handle) { await this.router.navigateByUrl(`/${perfil.handle}`); return; }
    } catch { /* O fallback cria a URL temporaria na pagina privada. */ }
    await this.router.navigateByUrl('/perfil');
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent) {
    const target = event.target as HTMLElement;
    if (!target.closest('.user-menu')) this.dropdownOpen = false;
    if (!target.closest('.notifications-menu')) this.notificationsOpen = false;
    if (!target.closest('.search-wrapper')) this.showSuggestions = false;
    if (!target.closest('.search-wrapper') && !target.closest('.search-toggle-btn')) this.buscaMobileAberta = false;
  }

  logout() {
    this.dropdownOpen = false;
    this.auth.logout();
  }
}
