import { Component, HostListener, ChangeDetectorRef, OnInit, OnDestroy } from '@angular/core';
import { Router, NavigationEnd } from '@angular/router';
import { Subject, Subscription } from 'rxjs';
import { debounceTime, distinctUntilChanged, filter, switchMap } from 'rxjs/operators';
import { ThemeService } from '../../services/theme';
import { MenuMobileService } from '../../services/menu-mobile';
import { AuthService } from '../../services/auth';
import { GameService, GameSummary } from '../../services/game';
import { SearchService } from '../../services/search';
import { PerfisService } from '../../services/perfis';
import { NotificationsService, PriceNotification } from '../../services/notifications';

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
  private isCatalogPage = false;
  private sub!: Subscription;
  private avatarSub!: Subscription;
  private routeSub!: Subscription;
  private searchSub!: Subscription;
  private notificationsSub!: Subscription;
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

    this.isCatalogPage = this.router.url.startsWith('/catalogo');
    this.routeSub = this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(e => {
        // Fecha o drawer tambem em navegacao que nao veio de clicar num link da sidebar (voltar
        // do navegador, redirect programatico) — sem isso ele ficaria aberto sobre a pagina nova.
        this.menuMobile.close();
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
          if (trimmed.length < 2) return [];
          return this.gameService.searchGames(trimmed);
        })
      )
      .subscribe(results => {
        this.suggestions = results.slice(0, 6);
        this.showSuggestions = this.suggestions.length > 0;
        this.cdr.detectChanges();
      });
  }

  ngOnDestroy() {
    this.sub.unsubscribe();
    this.avatarSub?.unsubscribe();
    this.routeSub?.unsubscribe();
    this.searchSub?.unsubscribe();
    this.notificationsSub?.unsubscribe();
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
  toggleNotifications() { this.notificationsOpen = !this.notificationsOpen; }

  get unreadNotifications(): number { return this.notifications.filter(notification => !notification.lida).length; }

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
  }

  logout() {
    this.dropdownOpen = false;
    this.auth.logout();
  }
}
