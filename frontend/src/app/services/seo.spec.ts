import { TestBed } from '@angular/core/testing';
import { Meta, Title } from '@angular/platform-browser';
import { SeoService } from './seo';

describe('SeoService', () => {
  let seo: SeoService;
  let title: Title;
  let meta: Meta;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    seo = TestBed.inject(SeoService);
    title = TestBed.inject(Title);
    meta = TestBed.inject(Meta);
  });

  it('define o título com o sufixo do site e a meta description', () => {
    seo.set({ title: 'Baldur\'s Gate 3', description: 'Compare preços.' });
    expect(title.getTitle()).toBe('Baldur\'s Gate 3 | Oferta Games');
    expect(meta.getTag('name="description"')?.content).toBe('Compare preços.');
  });

  it('preenche og:image com a imagem passada, ou com a imagem padrão quando ausente', () => {
    seo.set({ title: 'Jogo X', description: 'Desc', image: 'https://cdn.exemplo/capa.jpg' });
    expect(meta.getTag('property="og:image"')?.content).toBe('https://cdn.exemplo/capa.jpg');

    seo.set({ title: 'Jogo Y', description: 'Desc' });
    expect(meta.getTag('property="og:image"')?.content).toContain('og-image.png');
    expect(meta.getTag('property="og:image:width"')?.content).toBe('1200');
  });

  it('monta a og:url absoluta a partir do path', () => {
    seo.set({ title: 'Jogo X', description: 'Desc', path: '/jogo/jogo-x' });
    expect(meta.getTag('property="og:url"')?.content).toBe('https://ofertagames.vercel.app/jogo/jogo-x');
  });

  it('reset() volta pro título/descrição genéricos do site', () => {
    seo.set({ title: 'Jogo X', description: 'Descrição específica do jogo.' });
    seo.reset();
    expect(title.getTitle()).toBe('Compare preços e encontre as melhores promoções | Oferta Games');
    expect(meta.getTag('name="description"')?.content).not.toBe('Descrição específica do jogo.');
  });

  it('define canonical só quando a página informa o path, e tira em página noindex', () => {
    seo.set({ title: 'Jogo X', description: 'Desc', path: '/jogo/jogo-x' });
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href'))
      .toBe('https://ofertagames.vercel.app/jogo/jogo-x');

    seo.naoEncontrado();
    expect(document.head.querySelector('link[rel="canonical"]')).toBeNull();
    expect(meta.getTag('name="robots"')?.content).toBe('noindex');
    expect(title.getTitle()).toBe('Página não encontrada | Oferta Games');

    seo.set({ title: 'Jogo Y', description: 'Desc' });
    expect(meta.getTag('name="robots"')).toBeNull();
  });

  it('JSON-LD escapa "<" pra um título não fechar a tag script, e set() limpa o anterior', () => {
    seo.set({ title: 'Jogo', description: 'Desc' });
    seo.dadosEstruturados({ name: 'Jogo </script><script>alert(1)</script>' });
    const script = document.getElementById('seo-json-ld');
    expect(script?.textContent).not.toContain('</script>');
    expect(JSON.parse(script!.textContent!).name).toBe('Jogo </script><script>alert(1)</script>');

    seo.set({ title: 'Outra', description: 'Desc' });
    expect(document.getElementById('seo-json-ld')).toBeNull();
  });

  it('atualiza a tag existente em vez de duplicar ao chamar set() duas vezes', () => {
    seo.set({ title: 'Primeiro', description: 'Um' });
    seo.set({ title: 'Segundo', description: 'Dois' });
    const tags = meta.getTags('name="description"');
    expect(tags.length).toBe(1);
    expect(tags[0].content).toBe('Dois');
  });
});
