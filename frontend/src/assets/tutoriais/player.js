/* Engine compartilhada dos guias do JcardApp.
   Cada guia define window.CAPTIONS = [[lead, resto], ...] e window.DURS = [ms, ...],
   e marca as <section class="scene"> dentro de #stage. */
(function(){
  const captions = window.CAPTIONS || [];
  const DUR = window.DURS || captions.map(() => 8000);
  const scenes = [...document.querySelectorAll('.scene')];
  const segsEl = document.getElementById('segs');
  const capEl = document.getElementById('caption');
  const stepNow = document.getElementById('stepNow');
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;

  scenes.forEach((_, i) => {
    const s = document.createElement('div'); s.className = 'seg'; s.innerHTML = '<i></i>';
    s.addEventListener('click', () => goTo(i, true));
    segsEl.appendChild(s);
  });
  const segEls = [...segsEl.children];
  const totEl = document.getElementById('stepTot');
  if (totEl) totEl.textContent = scenes.length;

  let cur = 0, playing = true, t0 = 0, raf = 0, elapsed = 0;
  const playBtn = document.getElementById('play');
  const replay = document.getElementById('replay');

  function renderCaption(i){
    if (!captions[i]) { capEl.textContent = ''; return; }
    capEl.innerHTML = '<span class="lead">' + captions[i][0] + '</span>' + captions[i][1];
  }
  function setScene(i){
    scenes.forEach((s, k) => s.classList.toggle('on', k === i));
    segEls.forEach((s, k) => { s.classList.toggle('done', k < i); s.querySelector('i').style.width = k < i ? '100%' : '0'; });
    if (stepNow) stepNow.textContent = i + 1;
    renderCaption(i);
  }
  function goTo(i, fromClick){
    if (replay) replay.classList.remove('show');
    cur = (i + scenes.length) % scenes.length;
    setScene(cur); elapsed = 0; t0 = performance.now();
    if (fromClick && !playing) setPlay(true);
    if (playing) loop();
  }
  function tick(now){
    if (!playing) return;
    const frac = Math.min((now - t0) / DUR[cur], 1);
    segEls[cur].querySelector('i').style.width = (frac * 100) + '%';
    if (frac >= 1){
      if (cur === scenes.length - 1){ finish(); return; }
      goTo(cur + 1); return;
    }
    raf = requestAnimationFrame(tick);
  }
  function loop(){ cancelAnimationFrame(raf); t0 = performance.now() - elapsed; raf = requestAnimationFrame(tick); }
  function setPlay(p){
    playing = p;
    if (playBtn){ playBtn.textContent = p ? '⏸' : '▶'; playBtn.setAttribute('aria-label', p ? 'Pausar' : 'Reproduzir'); }
    if (p) loop(); else { cancelAnimationFrame(raf); elapsed = performance.now() - t0; }
  }
  function finish(){
    playing = false; if (playBtn) playBtn.textContent = '▶';
    segEls[cur].querySelector('i').style.width = '100%'; segEls[cur].classList.add('done');
    if (replay) replay.classList.add('show');
  }

  if (playBtn) playBtn.addEventListener('click', () => setPlay(!playing));
  const nx = document.getElementById('next'), pv = document.getElementById('prev'), rb = document.getElementById('replayBtn');
  if (nx) nx.addEventListener('click', () => goTo(cur + 1, true));
  if (pv) pv.addEventListener('click', () => goTo(cur - 1, true));
  if (rb) rb.addEventListener('click', () => { goTo(0); setPlay(true); });

  // Teclado: setas passam de cena e espaço pausa, sem precisar mirar o botão.
  document.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowRight') { goTo(cur + 1, true); }
    else if (e.key === 'ArrowLeft') { goTo(cur - 1, true); }
    else if (e.key === ' ' && e.target === document.body) { e.preventDefault(); setPlay(!playing); }
  });

  setScene(0);
  if (reduce){ setPlay(false); } else { t0 = performance.now(); raf = requestAnimationFrame(tick); }
})();
