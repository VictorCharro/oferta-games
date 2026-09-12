import { Component, ChangeDetectorRef, HostListener, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterModule } from '@angular/router';
import { CdkDragDrop, DragDropModule, moveItemInArray } from '@angular/cdk/drag-drop';
import { PerfilBloco, PerfilPublico, PerfisService } from '../../services/perfis';
import { CATALOGO_BLOCOS, DescricaoBloco, TIPOS_COM_VISUALIZACAO, TIPOS_UNICOS, blocosPadrao, descricaoBloco, novoBloco, visualizacaoDoBloco } from '../../services/perfil-blocos';
import { PublicProfile } from '../public-profile/public-profile';
import { SeoService } from '../../services/seo';

/**
 * Pagina dedicada de organizacao dos blocos do perfil (/perfil/blocos).
 * Complementa - nao substitui - o modo "Organizar" inline do perfil: aqui o foco e a ordem,
 * a visibilidade e o tamanho de cada bloco numa lista legivel; os ajustes finos que dependem
 * do conteudo renderizado (recorte de imagem, texto livre) continuam no editor do perfil.
 */
@Component({
  selector: 'app-editar-blocos',
  imports: [CommonModule, FormsModule, RouterModule, DragDropModule, PublicProfile],
  templateUrl: './editar-blocos.html',
  styleUrl: './editar-blocos.scss',
})
export class EditarBlocos implements OnInit {
  aba: 'blocos' | 'preview' = 'blocos';
  loading = true;
  saving = false;
  confirmandoModoAntigo = false;
  /** Como os blocos estavam na ultima carga/salvamento, pra saber se ha rascunho pendente. */
  private snapshotSalvo = '';
  erro = '';
  message = '';
  handle = '';
  blocos: PerfilBloco[] = [];
  perfil: PerfilPublico | null = null;
  menuAbertoId: string | null = null;

  readonly catalogo = CATALOGO_BLOCOS;
  readonly tamanhos: Array<{ valor: PerfilBloco['tamanho']; rotulo: string }> = [
    { valor: 'pequeno', rotulo: 'Pequeno' },
    { valor: 'medio', rotulo: 'Médio' },
    { valor: 'largo', rotulo: 'Largo' },
    { valor: 'completo', rotulo: 'Largura total' },
  ];

  constructor(
    private perfis: PerfisService,
    private router: Router,
    private cdr: ChangeDetectorRef,
    private seo: SeoService,
  ) {}

  ngOnInit() {
    this.seo.set({ title: 'Editar blocos do perfil', description: 'Organize os blocos que aparecem no seu perfil público.' });
    void this.carregar();
  }

  private async carregar() {
    try {
      const proprio = await this.perfis.proprio();
      if (!proprio?.handle) {
        this.erro = 'Defina um @handle nas configurações antes de organizar os blocos.';
        return;
      }
      this.handle = proprio.handle;
      // publico() so alimenta as regras de "tem dado pra mostrar?" da biblioteca de blocos; os
      // blocos vem de meusBlocos(), que traz tambem os ocultos (o publico filtra eles).
      const [perfil, meus] = await Promise.all([this.perfis.publico(proprio.handle), this.perfis.meusBlocos()]);
      this.perfil = perfil;
      // Mesma limpeza do perfil: tipos aposentados saem, corTexto vira null explicito.
      const salvos = (meus || [])
        .filter(bloco => !['resumo_favoritos', 'horas', 'conquistas'].includes(bloco.tipo as string))
        .map(bloco => ({ ...bloco, corTexto: bloco.corTexto ?? null, visualizacao: bloco.visualizacao ?? null }));
      this.blocos = salvos.length ? salvos : blocosPadrao();
      this.snapshotSalvo = JSON.stringify(this.blocos);
    } catch {
      this.erro = 'Não foi possível carregar seus blocos.';
    } finally {
      this.loading = false;
      this.cdr.detectChanges();
    }
  }

  temVisualizacao(bloco: PerfilBloco): boolean {
    return TIPOS_COM_VISUALIZACAO.includes(bloco.tipo);
  }

  visualizacao(bloco: PerfilBloco): 'cards' | 'lista' {
    return visualizacaoDoBloco(bloco);
  }

  definirVisualizacao(bloco: PerfilBloco, modo: 'cards' | 'lista') {
    bloco.visualizacao = modo;
  }

  /** Texto e links tem conteudo editavel aqui mesmo; imagem precisa do recorte sobre o bloco renderizado. */
  editavelNaPagina(bloco: PerfilBloco): boolean {
    return bloco.tipo === 'texto' || bloco.tipo === 'links';
  }

  get totalAtivos(): number {
    return this.blocos.filter(bloco => bloco.visivel).length;
  }

  descricao(bloco: PerfilBloco): DescricaoBloco {
    return descricaoBloco(bloco.tipo);
  }

  tituloDoBloco(bloco: PerfilBloco): string {
    return bloco.titulo?.trim() || this.descricao(bloco).titulo;
  }

  rotuloTamanho(bloco: PerfilBloco): string {
    return this.tamanhos.find(item => item.valor === bloco.tamanho)?.rotulo || bloco.tamanho;
  }

  toggleMenu(bloco: PerfilBloco) {
    this.menuAbertoId = this.menuAbertoId === bloco.id ? null : bloco.id;
  }

  // Fecha o menu "..." ao clicar em qualquer lugar fora dele (mesmo comportamento dos menus
  // do editor do perfil). Cliques dentro do proprio menu-wrap seguem normalmente.
  @HostListener('document:click', ['$event'])
  aoClicarFora(event: MouseEvent) {
    if (!this.menuAbertoId) return;
    if ((event.target as HTMLElement | null)?.closest('.menu-wrap')) return;
    this.menuAbertoId = null;
  }

  fecharMenu() {
    this.menuAbertoId = null;
  }

  alternarVisivel(bloco: PerfilBloco) {
    bloco.visivel = !bloco.visivel;
  }

  definirTamanho(bloco: PerfilBloco, tamanho: PerfilBloco['tamanho']) {
    bloco.tamanho = tamanho;
  }

  definirCorFundo(bloco: PerfilBloco, cor: string) {
    bloco.tipoFundo = 'cor';
    bloco.valorFundo = cor;
  }

  limparFundo(bloco: PerfilBloco) {
    bloco.tipoFundo = 'padrao';
    bloco.valorFundo = null;
  }

  definirCorTexto(bloco: PerfilBloco, cor: string) {
    bloco.corTexto = cor.trim() || null;
  }

  limparCorTexto(bloco: PerfilBloco) {
    bloco.corTexto = null;
  }

  mover(indice: number, direcao: -1 | 1) {
    const destino = indice + direcao;
    if (destino < 0 || destino >= this.blocos.length) return;
    moveItemInArray(this.blocos, indice, destino);
    this.renumerar();
  }

  drop(event: CdkDragDrop<PerfilBloco[]>) {
    moveItemInArray(this.blocos, event.previousIndex, event.currentIndex);
    this.renumerar();
  }

  remover(indice: number) {
    this.blocos.splice(indice, 1);
    this.renumerar();
    this.fecharMenu();
  }

  /** Mesmas regras do dropdown "Adicionar" do perfil: unicos so uma vez, e so com dado pra mostrar. */
  indisponivel(tipo: PerfilBloco['tipo']): boolean {
    if (tipo === 'wishlist' && !this.perfil?.temColecaoWishlistSteam) return true;
    if (tipo === 'conquistas-recentes' && !this.perfil?.conquistasRecentes?.length) return true;
    if (tipo === 'mais-jogados' && !this.perfil?.biblioteca?.length) return true;
    return TIPOS_UNICOS.includes(tipo) && this.blocos.some(bloco => bloco.tipo === tipo);
  }

  adicionar(tipo: PerfilBloco['tipo']) {
    if (this.indisponivel(tipo)) return;
    this.blocos.push(novoBloco(tipo, this.blocos.length));
  }

  private renumerar() {
    this.blocos.forEach((bloco, posicao) => { bloco.posicao = posicao; });
  }

  async salvar() {
    this.saving = true;
    this.message = '';
    this.renumerar();
    try {
      await this.perfis.salvarBlocos(this.blocos);
      this.snapshotSalvo = JSON.stringify(this.blocos);
      this.message = 'Blocos salvos.';
    } catch {
      this.message = 'Não foi possível salvar os blocos.';
    }
    this.saving = false;
    this.cdr.detectChanges();
  }

  async salvarEVoltar() {
    await this.salvar();
    if (this.message === 'Blocos salvos.') void this.router.navigate(['/', this.handle]);
  }

  /** Ha rascunho nao salvo? Comparacao por serializacao: a lista e pequena (max 20 blocos). */
  get temRascunho(): boolean {
    return this.snapshotSalvo !== '' && JSON.stringify(this.blocos) !== this.snapshotSalvo;
  }

  /**
   * Abre o perfil no modo de edicao na pagina (`?editor=1`), onde o dono mexe nos blocos vendo
   * o perfil de verdade. E o unico lugar com recorte de imagem, gradiente e cor global.
   *
   * <p>Sair daqui abandona o rascunho (a edicao na pagina le o que esta salvo), entao com alteracao
   * pendente pergunta antes em vez de perder o trabalho silenciosamente.
   */
  abrirModoAntigo() {
    if (this.temRascunho) {
      this.confirmandoModoAntigo = true;
      return;
    }
    this.irParaModoAntigo();
  }

  async salvarEAbrirModoAntigo() {
    await this.salvar();
    if (this.message === 'Blocos salvos.') this.irParaModoAntigo();
  }

  irParaModoAntigo() {
    this.confirmandoModoAntigo = false;
    void this.router.navigate(['/', this.handle], { queryParams: { editor: 1 } });
  }
}
