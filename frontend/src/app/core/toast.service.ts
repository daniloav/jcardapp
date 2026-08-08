import { Injectable, signal } from '@angular/core';

export interface Toast {
  id: number;
  texto: string;
  tipo: 'ok' | 'erro';
}

/** Avisos curtos no rodapé. Um lugar só para o app inteiro dar retorno. */
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly toasts = signal<Toast[]>([]);
  private proximoId = 1;

  ok(texto: string): void {
    this.mostrar(texto, 'ok');
  }

  erro(texto: string): void {
    this.mostrar(texto, 'erro');
  }

  fechar(id: number): void {
    this.toasts.update((lista) => lista.filter((t) => t.id !== id));
  }

  private mostrar(texto: string, tipo: 'ok' | 'erro'): void {
    const id = this.proximoId++;
    this.toasts.update((lista) => [...lista, { id, texto, tipo }]);
    // Erro fica mais tempo: costuma trazer instrução do que fazer.
    setTimeout(() => this.fechar(id), tipo === 'erro' ? 6000 : 3500);
  }
}
