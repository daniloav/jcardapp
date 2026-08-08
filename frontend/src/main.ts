import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { registerLocaleData } from '@angular/common';
import ptBr from '@angular/common/locales/pt';
import { LOCALE_ID } from '@angular/core';
import { ShellComponent } from './app/layout/shell.component';
import { authInterceptor } from './app/core/auth.interceptor';
import { rotas } from './app/app.routes';

// Valores e datas em pt-BR no app inteiro: é um app de dinheiro brasileiro.
registerLocaleData(ptBr);

bootstrapApplication(ShellComponent, {
  providers: [
    provideRouter(rotas, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor])),
    { provide: LOCALE_ID, useValue: 'pt-BR' },
  ],
}).catch((e) => console.error(e));
