import { Injectable } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';

export interface SeoConfig {
  title: string;
  description: string;
  image?: string | null;
  path?: string;
}

const SITE_NAME = 'Oferta Games';
const SITE_URL = 'https://ofertagames.vercel.app';
const DEFAULT_IMAGE = `${SITE_URL}/logo.png`;

// Title/Meta funcionam identicos no browser e no server (Angular so troca o backend por baixo),
// entao rodam durante o SSR e o HTML ja sai com o titulo/descricao certos pro Google e pra previews
// de link (WhatsApp/Twitter/Discord), sem depender do JS do visitante rodar.
@Injectable({ providedIn: 'root' })
export class SeoService {
  constructor(private title: Title, private meta: Meta) {}

  set(config: SeoConfig) {
    const fullTitle = `${config.title} | ${SITE_NAME}`;
    const url = SITE_URL + (config.path ?? '');
    const image = config.image || DEFAULT_IMAGE;

    this.title.setTitle(fullTitle);
    this.setTag('description', config.description);
    this.setTag('og:title', fullTitle);
    this.setTag('og:description', config.description);
    this.setTag('og:image', image);
    this.setTag('og:url', url);
    this.setTag('og:type', 'website');
    this.setTag('og:site_name', SITE_NAME);
    this.setTag('twitter:card', 'summary_large_image');
    this.setTag('twitter:title', fullTitle);
    this.setTag('twitter:description', config.description);
    this.setTag('twitter:image', image);
  }

  // Volta pro titulo/descricao genericos: usado ao sair de uma pagina com meta customizada (ex:
  // detalhe de jogo) pra uma que nao define nada especifico, senao o titulo anterior "vazaria".
  reset() {
    this.set({
      title: 'Compare preços e encontre as melhores promoções',
      description: 'Compare preços de jogos nas melhores lojas e encontre as maiores promoções.',
    });
  }

  private setTag(property: string, content: string) {
    const isOg = property.startsWith('og:');
    const selector = isOg ? `property="${property}"` : `name="${property}"`;
    if (this.meta.getTag(selector)) {
      this.meta.updateTag({ [isOg ? 'property' : 'name']: property, content });
    } else {
      this.meta.addTag({ [isOg ? 'property' : 'name']: property, content });
    }
  }
}
