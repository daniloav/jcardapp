/*
 * Service worker do JcardApp.
 *
 * Cacheia SÓ o app shell (HTML/JS/CSS/ícones). Nenhuma resposta de /api entra
 * em cache: são lançamentos, valores e nomes de pessoas — dado financeiro não
 * pode sobrar no disco do navegador depois do logout, nem ser servido
 * desatualizado (um lançamento já assumido por outra pessoa reapareceria como
 * disponível).
 *
 * Estratégia: network-first para o shell, com fallback ao cache quando offline.
 * Assim um deploy novo aparece na hora, sem esperar o SW expirar.
 */
const VERSAO = 'jcard-shell-v1';
const SHELL = ['/', '/index.html', '/manifest.webmanifest'];

self.addEventListener('install', (evento) => {
  evento.waitUntil(
    caches.open(VERSAO)
      .then((cache) => cache.addAll(SHELL))
      .then(() => self.skipWaiting())
      .catch(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (evento) => {
  evento.waitUntil(
    caches.keys()
      .then((chaves) => Promise.all(
        chaves.filter((c) => c !== VERSAO).map((c) => caches.delete(c))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (evento) => {
  const req = evento.request;
  const url = new URL(req.url);

  // Só GET do próprio domínio. API e mutações passam direto para a rede.
  if (req.method !== 'GET'
      || url.origin !== self.location.origin
      || url.pathname.startsWith('/api')
      || url.pathname.startsWith('/q')) {
    return;
  }

  evento.respondWith(
    fetch(req)
      .then((resposta) => {
        if (resposta && resposta.status === 200 && resposta.type === 'basic') {
          const copia = resposta.clone();
          caches.open(VERSAO).then((cache) => cache.put(req, copia));
        }
        return resposta;
      })
      .catch(() => caches.match(req).then((c) => c || caches.match('/index.html')))
  );
});
