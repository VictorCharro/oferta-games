import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { SeoService } from '../../services/seo';
import { EMAIL_CONTATO, REVISAO_TEXTOS_LEGAIS } from '../../configuracao/contato';

type TipoPagina = 'privacidade' | 'termos';

/**
 * Politica de Privacidade e Termos de Uso (issue #18).
 *
 * Os textos descrevem o que o sistema FAZ hoje — dados coletados, onde ficam, com quem sao
 * compartilhados. Mudou o tratamento (nova integracao, analytics, novo provedor), o texto precisa
 * mudar junto, e a data em REVISAO_TEXTOS_LEGAIS tambem.
 */
@Component({
  selector: 'app-legal',
  imports: [CommonModule, RouterLink],
  templateUrl: './legal.html',
  styleUrl: './legal.scss',
})
export class Legal implements OnInit {
  tipo: TipoPagina = 'privacidade';
  readonly email = EMAIL_CONTATO;
  readonly revisao = REVISAO_TEXTOS_LEGAIS;

  constructor(private route: ActivatedRoute, private seo: SeoService) {}

  ngOnInit() {
    this.tipo = this.route.snapshot.data['tipo'] === 'termos' ? 'termos' : 'privacidade';
    this.seo.set(this.tipo === 'termos'
      ? { title: 'Termos de Uso', description: 'Regras de uso do Oferta Games: preços, links para lojas, conta e conteúdo do perfil.', path: '/termos' }
      : { title: 'Política de Privacidade', description: 'Quais dados o Oferta Games coleta, para quê, com quem compartilha e como exercer seus direitos (LGPD).', path: '/privacidade' });
  }
}
