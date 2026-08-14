import { Injectable, signal } from '@angular/core';

/**
 * Lê o texto de um print da fatura — no aparelho de quem subiu, não no servidor.
 *
 * A VM é uma e2-micro: 1 GB de memória e uma vCPU compartilhada, dividida com o
 * Quarkus. Reconhecimento de imagem ali derrubaria o app no meio do mês, e
 * mandar a foto para o servidor guardaria imagem de fatura em disco sem
 * necessidade. Aqui a imagem não sai do aparelho: sobe só o texto.
 *
 * O motor (WebAssembly) e o modelo de português vêm de `/assets/ocr`, copiados
 * do `node_modules` no build. Nada de CDN: o app tem de funcionar sem depender
 * de terceiro, e é o mesmo motivo pelo qual os treinamentos são HTML nosso.
 *
 * Carregado sob demanda com `import()`: são ~10 MB que só quem vai ler um print
 * baixa, e uma vez só — o cache do navegador guarda entre um print e outro.
 */
@Injectable({ providedIn: 'root' })
export class OcrService {
  /** 0 a 1 enquanto lê; a tela precisa dizer que não travou. */
  progresso = signal(0);
  preparando = signal(false);

  private worker: unknown = null;

  /**
   * Devolve o texto reconhecido na imagem.
   *
   * O worker é criado uma vez e reaproveitado: quem monta a prévia a print sobe
   * vários seguidos, e recriar o motor a cada um relê os 10 MB de modelo.
   */
  async ler(imagem: File | Blob): Promise<string> {
    const worker = await this.motor();
    this.progresso.set(0);
    const { data } = await (worker as {
      recognize: (i: File | Blob) => Promise<{ data: { text: string } }>;
    }).recognize(imagem);
    this.progresso.set(1);
    return data.text ?? '';
  }

  /** Libera o motor. A tela chama ao sair: são ~10 MB presos em memória. */
  async encerrar(): Promise<void> {
    const w = this.worker as { terminate?: () => Promise<void> } | null;
    this.worker = null;
    await w?.terminate?.();
  }

  private async motor(): Promise<unknown> {
    if (this.worker) {
      return this.worker;
    }
    this.preparando.set(true);
    try {
      const { createWorker } = await import('tesseract.js');
      // URLs absolutas, e não "/assets/...": o motor roda dentro de um worker
      // criado a partir de um blob, e ali caminho relativo não tem origem para
      // resolver — o navegador recusa com "URL is invalid".
      const base = `${location.origin}/assets/ocr`;
      this.worker = await createWorker('por', 1, {
        workerPath: `${base}/worker.min.js`,
        corePath: base,
        langPath: `${base}/lang`,
        gzip: true,
        logger: (m: { status: string; progress: number }) => {
          if (m.status === 'recognizing text') {
            this.progresso.set(m.progress);
          }
        },
      });
      return this.worker;
    } finally {
      this.preparando.set(false);
    }
  }
}
