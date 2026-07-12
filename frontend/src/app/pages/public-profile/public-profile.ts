import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { PerfilPublico, PerfisService } from '../../services/perfis';

@Component({ selector: 'app-public-profile', standalone: false, templateUrl: './public-profile.html', styleUrl: './public-profile.scss' })
export class PublicProfile implements OnInit {
  profile: PerfilPublico | null = null;
  missing = false;
  loading = true;
  activeTab: 'resumo' | 'biblioteca' = 'resumo';
  constructor(private route: ActivatedRoute, private perfis: PerfisService, private cdr: ChangeDetectorRef) {}
  async ngOnInit() { try { this.profile = await this.perfis.publico(this.route.snapshot.paramMap.get('handle') || ''); } catch { this.missing = true; } finally { this.loading = false; this.cdr.detectChanges(); } }
  hours(minutes: number | null): string { if (minutes == null) return ''; return `${Math.floor(minutes / 60)}h ${minutes % 60}m`; }
  icon(game: PerfilPublico['biblioteca'][number]): string { return game.iconeHash ? `https://media.steampowered.com/steamcommunity/public/images/apps/${game.appId}/${game.iconeHash}.jpg` : 'store-logos/steam.svg'; }
}
