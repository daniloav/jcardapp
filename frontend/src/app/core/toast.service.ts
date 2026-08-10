import { Injectable, signal } from '@angular/core';

/**
 * Uma ação oferecida junto com o aviso — hoje só o "desfazer".
 *
 * <p>Existe porque o engano mais fácil do app (marcar "foi minha" no lançamento
 * errado, numa lista longa, no celular) fica muito mais barato de corrigir no
 * segundo seguinte do que depois, quando vira conversa com o administrador.
 */
export interface AcaoToast {
  texto: string;
  executar: () => void;
}

export interface Toast {
  id: number;
  texto: string;
  tipo: 'ok' | 'erro';
  acao?: AcaoToast;
}

/** Avisos curtos no rodapé. Um lugar só para o app inteiro dar retorno. */
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<Toast[]>([]);
  private proximoId = 1;

  ok(texto: string, acao?: AcaoToast): void {
    this.mostrar(texto, 'ok', acao);
  }

  erro(texto: string): void {
    this.mostrar(texto, 'erro');
  }

  fechar(id: number): void {
    this.toasts.update((lista) => lista.filter((t) => t.id !== id));
  }

  /** Roda a ação e fecha o aviso: ela só vale uma vez. */
  executar(t: Toast): void {
    this.fechar(t.id);
    t.acao?.executar();
  }

  private mostrar(texto: string, tipo: 'ok' | 'erro', acao?: AcaoToast): void {
    const id = this.proximoId++;
    this.toasts.update((lista) => [...lista, { id, texto, tipo, acao }]);
    // Erro fica mais tempo: costuma trazer instrução do que fazer. Com ação,
    // mais ainda — 3,5 s não dá para ler, decidir e tocar num botão.
    const duracao = tipo === 'erro' ? 6000 : acao ? 8000 : 3500;
    setTimeout(() => this.fechar(id), duracao);
  }
}
