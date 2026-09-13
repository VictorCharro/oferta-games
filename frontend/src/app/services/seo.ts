import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';

export interface SeoConfig {
  title: string;
  description: string;
  image?: string | null;
  path?: string;
  /** Pagina que nao deve ir pro indice do Google (nao encontrado, erro, area logada). */
  noindex?: boolean;
}

const SITE_NAME = 'Oferta Games';
// Unico lugar com o dominio publico do site: og:url, canonical e JSON-LD saem daqui. Na troca pro
// dominio proprio (issue #19) e so mudar esta constante.
export const SITE_URL = 'https://ofertagames.vercel.app';
const DEFAULT_IMAGE = `${SITE_URL}/logo.png`;
const ID_JSON_LD = 'seo-json-ld';

// Title/Meta funcionam identicos no browser e no server (Angular so troca o backend por baixo),
// entao rodam durante o SSR e o HTML ja sai com o titulo/descricao certos pro Google e pra previews
// de link (WhatsApp/Twitter/Discord), sem depender do JS do visitante rodar.
@Injectable({ providedIn: 'root' })
export class SeoService {
  private readonly document = inject(DOCUMENT);

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
    this.setTag('og:locale', 'pt_BR');
    this.setTag('twitter:card', 'summary_large_image');
    this.setTag('twitter:title', fullTitle);
    this.setTag('twitter:description', config.description);
    this.setTag('twitter:image', image);

    if (config.noindex) this.setTag('robots', 'noindex');
    else this.meta.removeTag('name="robots"');

    // Canonical so quando a pagina diz qual e o proprio endereco. Sem path, nao chuta: um
    // canonical errado (ex. todas as paginas apontando pra Home) e pior que nenhum.
    this.definirCanonical(config.path != null && !config.noindex ? url : null);
    this.dadosEstruturados(null);
  }

  // Volta pro titulo/descricao genericos: usado ao sair de uma pagina com meta customizada (ex:
  // detalhe de jogo) pra uma que nao define nada especifico, senao o titulo anterior "vazaria".
  reset() {
    this.set({
      title: 'Compare preços e encontre as melhores promoções',
      description: 'Compare preços de jogos nas melhores lojas e encontre as maiores promoções.',
    });
  }

  /** "Nao encontrado" com titulo proprio e fora do indice. O status 404 fica com StatusResposta. */
  naoEncontrado() {
    this.set({
      title: 'Página não encontrada',
      description: 'A página que você procura não existe ou foi removida.',
      noindex: true,
    });
  }

  /**
   * JSON-LD (schema.org) da pagina. Chamar DEPOIS de {@link set}, que limpa o anterior.
   * `null` remove. Vai como texto de um <script type="application/ld+json">, nunca HTML.
   */
  dadosEstruturados(dados: object | null) {
    this.document.getElementById(ID_JSON_LD)?.remove();
    if (!dados) return;
    const script = this.document.createElement('script');
    script.id = ID_JSON_LD;
    script.type = 'application/ld+json';
    // "<" escapado: um titulo de jogo com "</script>" nao pode fechar a tag.
    script.textContent = JSON.stringify(dados).replace(/</g, '\\u003c');
    this.document.head.appendChild(script);
  }

  private definirCanonical(url: string | null) {
    let link = this.document.head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
    if (!url) {
      link?.remove();
      return;
    }
    if (!link) {
      link = this.document.createElement('link');
      link.setAttribute('rel', 'canonical');
      this.document.head.appendChild(link);
    }
    link.setAttribute('href', url);
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
