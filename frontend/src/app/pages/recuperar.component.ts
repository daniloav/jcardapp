import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Recuperação de acesso.
 *
 * Não há autoatendimento de propósito: quem reseta a senha é o admin, na tela de
 * utilizadores, e o app volta a senha para a provisória e manda o e-mail. Um
 * endpoint público que aceitasse um login e disparasse o reset diria a quem
 * perguntasse quais logins existem — num app onde o utilizador é alguém da
 * família, pedir ao admin não custa nada e não expõe a lista.
 */
@Component({
  standalone: true,
  imports: [RouterLink],
  styles: [`
    .caixa-auth { max-width: 420px; }
    .passos { margin: 0 0 1.25rem; padding-left: 1.2rem; font-size: 0.92rem; line-height: 1.6; }
    .passos li { margin-bottom: 0.5rem; }
    .btn-email {
      display: block;
      text-align: center;
      text-decoration: none;
      background: var(--azul);
      color: #fff;
      font-weight: 600;
      padding: 0.7rem;
      border-radius: var(--raio);
    }
    .btn-email:hover { background: var(--azul-claro); }
    .btn-email:focus-visible { outline: 3px solid var(--acento); outline-offset: 2px; }
  `],
  template: `
    <div class="tela-auth">
      <div class="caixa-auth">
        <div class="marca-auth">
          <div class="icone" aria-hidden="true">🔑</div>
          <h1>Esqueci minha senha</h1>
          <p>Quem devolve o acesso é o administrador do cartão.</p>
        </div>

        <div class="aviso info">
          O JcardApp não redefine senha sozinho: as contas são poucas e todas de
          gente conhecida. Peça o reset e a senha provisória chega no seu e-mail,
          com troca obrigatória no primeiro acesso.
        </div>

        <ol class="passos">
          <li>Mande o pedido para o administrador dizendo <strong>o seu login</strong>
              — ou o seu nome completo, se não lembrar dele.</li>
          <li>Ele reseta a senha na tela de utilizadores.</li>
          <li>Você recebe a senha provisória por e-mail e escolhe uma nova ao entrar.</li>
        </ol>

        <a class="btn-email"
           href="mailto:danilo.av&#64;gmail.com?subject=JcardApp%20-%20esqueci%20minha%20senha&amp;body=Oi%2C%20preciso%20resetar%20a%20minha%20senha%20do%20JcardApp.%0A%0AMeu%20login%3A%20">
          ✉️ Pedir o reset por e-mail
        </a>

        <a class="link-auth" routerLink="/login">← Voltar para o login</a>
      </div>
    </div>
  `,
})
export class RecuperarComponent {}
