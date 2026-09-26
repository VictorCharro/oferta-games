import { Injectable, PLATFORM_ID, inject, isDevMode } from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
import { BehaviorSubject } from 'rxjs';

export type TipoAviso = 'sucesso' | 'erro' | 'info';

export interface Aviso {
  id: number;
  tipo: TipoAviso;
  texto: string;
  /** Marcado pouco antes de sair da lista, pra animacao de saida ter tempo de rodar. */
  saindo: boolean;
}

/** Quantos avisos cabem na tela ao mesmo tempo; o mais antigo sai quando chega um novo. */
const MAXIMO_NA_TELA = 3;
/** Tem que bater com a duracao da animacao de saida em avisos.scss (--dur-medio). */
const DURACAO_SAIDA_MS = 200;

/**
 * Aviso flutuante de confirmacao (toast), 26/09/2026. Ate aqui o site nao tinha nenhum: acao que
 * dava certo (monitorar, salvar meta, criar lista) nao dizia nada, e o usuario ficava sem saber se
 * o clique tinha funcionado.
 *
 * <p>Uso: {@code avisos.sucesso('Jogo monitorado')}. Erro fica mais tempo na tela (da tempo de
 * ler o que corrigir). Texto repetido que ja esta na tela nao empilha: substitui o anterior.
 *
 * <p>So faz algo no navegador. No SSR nao ha quem leia, e um {@code setTimeout} pendente no
 * servidor so atrasaria a resposta.
 */
@Injectable({ providedIn: 'root' })
export class AvisosService {
  private readonly lista = new BehaviorSubject<Aviso[]>([]);
  readonly avisos$ = this.lista.asObservable();
  private proximoId = 0;
  private readonly noNavegador = isPlatformBrowser(inject(PLATFORM_ID));

  constructor() {
    // So em desenvolvimento: permite testar pelo console do navegador,
    // ex. avisos.sucesso('Teste'). Nao existe no build de producao.
    if (this.noNavegador && isDevMode()) (window as unknown as { avisos: AvisosService }).avisos = this;
  }

  sucesso(texto: string) { this.mostrar('sucesso', texto, 3500); }
  info(texto: string) { this.mostrar('info', texto, 3500); }
  erro(texto: string) { this.mostrar('erro', texto, 6000); }

  fechar(id: number) {
    const atual = this.lista.value;
    if (!atual.some(a => a.id === id && !a.saindo)) return;
    this.lista.next(atual.map(a => (a.id === id ? { ...a, saindo: true } : a)));
    setTimeout(() => this.lista.next(this.lista.value.filter(a => a.id !== id)), DURACAO_SAIDA_MS);
  }

  private mostrar(tipo: TipoAviso, texto: string, duracaoMs: number) {
    if (!this.noNavegador) return;
    const aviso: Aviso = { id: ++this.proximoId, tipo, texto, saindo: false };
    const semRepetido = this.lista.value.filter(a => !(a.tipo === tipo && a.texto === texto));
    this.lista.next([...semRepetido, aviso].slice(-MAXIMO_NA_TELA));
    setTimeout(() => this.fechar(aviso.id), duracaoMs);
  }
}
