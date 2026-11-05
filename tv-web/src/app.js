/*
 * RedTica TV — app para televisores Samsung (Tizen) y LG (webOS).
 * Mismo funcionamiento que la app de Android:
 *   ▲ ▼ / CH+ CH-  cambiar canal        Números  ir al canal
 *   OK / Menú      lista de canales     ◀        cambiar audio
 *   ▶              formato de pantalla  Atrás    cerrar lista / salir
 * Escrito en ES5 para que funcione también en televisores de 2016 en adelante.
 */
(function () {
  'use strict';

  var CFG = window.REDTICA_CONFIG;
  var CHANNELS = window.REDTICA_CHANNELS || [];

  // ---------- Plataforma ----------
  var isTizen = typeof window.webapis !== 'undefined' && window.webapis && window.webapis.avplay;
  var isWebOS = typeof window.PalmSystem !== 'undefined' || /Web0S|webOS/i.test(navigator.userAgent);
  if (isTizen) document.body.className += ' tizen';

  var KEY = {
    LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13,
    BACK_TIZEN: 10009, BACK_WEBOS: 461, BACKSPACE: 8, ESC: 27,
    CH_UP_TIZEN: 427, CH_DOWN_TIZEN: 428, PAGE_UP: 33, PAGE_DOWN: 34,
    RED: 403, GREEN: 404, MENU_TIZEN: 10133, INFO_TIZEN: 457
  };

  // Samsung: hay que registrar las teclas que no son flechas/OK/Atrás
  if (isTizen && window.tizen && tizen.tvinputdevice) {
    var keys = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
      'ChannelUp', 'ChannelDown', 'ColorF0Red', 'ColorF1Green', 'Menu', 'Info'];
    for (var k = 0; k < keys.length; k++) {
      try { tizen.tvinputdevice.registerKey(keys[k]); } catch (e) { /* tecla no disponible */ }
    }
  }

  // ---------- Elementos ----------
  var $video = document.getElementById('video');
  var $banner = document.getElementById('banner');
  var $status = document.getElementById('status');
  var $panel = document.getElementById('panel');
  var $list = document.getElementById('list');

  // ---------- Preferencias (por televisor) ----------
  function getPref(key, def) {
    try {
      var v = window.localStorage.getItem('redtica.' + key);
      return v === null ? def : v;
    } catch (e) { return def; }
  }
  function setPref(key, val) {
    try { window.localStorage.setItem('redtica.' + key, String(val)); } catch (e) { /* sin almacenamiento */ }
  }

  // ---------- Estado ----------
  var current = 0;
  var focus = 0;
  var typed = '';
  var typedTimer = null;
  var bannerTimer = null;
  var backArmed = false;
  var lastProgress = 0;
  var lastTime = -1;

  // Formatos de pantalla (en Samsung el reproductor nativo no tiene "zoom")
  var FORMATS = isTizen
    ? [{ name: 'Pantalla completa', tizen: 'PLAYER_DISPLAY_MODE_FULL_SCREEN' },
       { name: 'Tamaño original', tizen: 'PLAYER_DISPLAY_MODE_LETTER_BOX' }]
    : [{ name: 'Pantalla completa', css: '' },
       { name: 'Zoom (recorta bordes negros)', css: 'zoom' },
       { name: 'Tamaño original', css: 'fit' }];

  function url(ch) { return CFG.SERVER + '/play/' + ch.id; }

  // ---------- Mensajes ----------
  function showBanner(html, ms) {
    $banner.innerHTML = html;
    $banner.className = '';
    clearTimeout(bannerTimer);
    bannerTimer = setTimeout(function () { $banner.className = 'hidden'; }, ms || 3500);
  }
  function showStatus(msg) { $status.textContent = msg; $status.className = ''; }
  function hideStatus() { $status.className = 'hidden'; }
  function esc(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  // ---------- Reproductor ----------
  function stopPlayer() {
    if (isTizen) {
      try { webapis.avplay.stop(); } catch (e) { /* ya detenido */ }
      try { webapis.avplay.close(); } catch (e2) { /* ya cerrado */ }
    } else {
      try { $video.pause(); $video.removeAttribute('src'); $video.load(); } catch (e3) { /* nada */ }
    }
  }

  function play(index) {
    if (!CHANNELS.length) return;
    current = (index % CHANNELS.length + CHANNELS.length) % CHANNELS.length;
    var ch = CHANNELS[current];
    setPref('ultimo', ch.id);
    showBanner('<span class="num">' + ch.n + '</span>' + esc(ch.name));
    showStatus('Cargando…');
    lastProgress = Date.now();
    lastTime = -1;
    stopPlayer();

    if (isTizen) {
      var av = webapis.avplay;
      try {
        av.open(url(ch));
        av.setDisplayRect(0, 0, 1920, 1080);
        av.setListener({
          onbufferingcomplete: function () { hideStatus(); },
          onstreamcompleted: function () { retrySoon(); },
          onerror: function () { showStatus('Sin señal, reintentando…'); retrySoon(); }
        });
        av.prepareAsync(function () {
          applyFormat();
          applySavedAudio();
          av.play();
          hideStatus();
          lastProgress = Date.now();
        }, function () { showStatus('Sin señal, reintentando…'); retrySoon(); });
      } catch (e) {
        showStatus('Sin señal, reintentando…');
        retrySoon();
      }
    } else {
      $video.src = url(ch);
      applyFormat();
      var p = $video.play();
      if (p && p['catch']) p['catch'](function () { /* se reintenta con el vigilante */ });
    }
    renderList();
  }

  var retryTimer = null;
  function retrySoon() {
    clearTimeout(retryTimer);
    retryTimer = setTimeout(function () { play(current); }, 3000);
  }

  if (!isTizen) {
    $video.addEventListener('playing', function () { hideStatus(); lastProgress = Date.now(); });
    $video.addEventListener('loadedmetadata', function () { applySavedAudio(); });
    $video.addEventListener('error', function () { showStatus('Sin señal, reintentando…'); retrySoon(); });
  }

  // Vigilante: si la imagen se congela, se vuelve a abrir el canal
  setInterval(function () {
    if (!CHANNELS.length) return;
    var t = -1;
    try { t = isTizen ? webapis.avplay.getCurrentTime() : $video.currentTime; } catch (e) { t = -1; }
    if (t !== lastTime && t > 0) {
      lastTime = t;
      lastProgress = Date.now();
    } else if (Date.now() - lastProgress > (CFG.RETRY_SECONDS + 6) * 1000) {
      showStatus('Sin señal, reintentando…');
      play(current);
    }
  }, 1000);

  // ---------- Formato de pantalla ----------
  function formatIndex() {
    var i = parseInt(getPref('formato.' + CHANNELS[current].id, '0'), 10);
    return isNaN(i) || i < 0 || i >= FORMATS.length ? 0 : i;
  }
  function applyFormat() {
    var f = FORMATS[formatIndex()];
    if (isTizen) {
      try { webapis.avplay.setDisplayMethod(f.tizen); } catch (e) { /* no soportado */ }
    } else {
      $video.className = f.css;
    }
  }
  function cycleFormat() {
    var next = (formatIndex() + 1) % FORMATS.length;
    setPref('formato.' + CHANNELS[current].id, next);
    applyFormat();
    showBanner(FORMATS[next].name, 2500);
  }

  // ---------- Audio ----------
  var LANG = { spa: 'Español', es: 'Español', esp: 'Español', eng: 'Inglés', en: 'Inglés', por: 'Portugués', pt: 'Portugués' };

  // Devuelve [{lang, select()}] según la plataforma
  function audioTracks() {
    var out = [];
    if (isTizen) {
      try {
        var info = webapis.avplay.getTotalTrackInfo();
        for (var i = 0; i < info.length; i++) {
          if (info[i].type !== 'AUDIO') continue;
          var lang = '';
          try { lang = JSON.parse(info[i].extra_info).language || ''; } catch (e) { lang = ''; }
          (function (idx, l) {
            out.push({ lang: l, select: function () { webapis.avplay.setSelectTrack('AUDIO', idx); } });
          })(info[i].index, lang);
        }
      } catch (e2) { /* sin información */ }
    } else if ($video.audioTracks) {
      var at = $video.audioTracks;
      for (var j = 0; j < at.length; j++) {
        (function (n) {
          out.push({
            lang: at[n].language || '',
            select: function () { for (var m = 0; m < at.length; m++) at[m].enabled = (m === n); }
          });
        })(j);
      }
    }
    return out;
  }

  function langName(t, pos) {
    var l = (t.lang || '').toLowerCase();
    return LANG[l] || (l && l !== 'und' ? l.toUpperCase() : 'Audio ' + (pos + 1));
  }

  // Al abrir el canal: audio guardado por el cliente, o español si existe
  function applySavedAudio() {
    var tracks = audioTracks();
    if (tracks.length < 2) return;
    var saved = parseInt(getPref('audio.' + CHANNELS[current].id, '-1'), 10);
    if (saved >= 0 && saved < tracks.length) { tracks[saved].select(); return; }
    for (var i = 0; i < tracks.length; i++) {
      if (/^(spa|es|esp)/i.test(tracks[i].lang)) { tracks[i].select(); return; }
    }
  }

  var audioPos = {};
  function cycleAudio() {
    var tracks = audioTracks();
    if (tracks.length < 2) { showBanner('Este canal tiene un solo audio', 2500); return; }
    var id = CHANNELS[current].id;
    var cur = parseInt(getPref('audio.' + id, String(audioPos[id] || 0)), 10) || 0;
    var next = (cur + 1) % tracks.length;
    tracks[next].select();
    audioPos[id] = next;
    setPref('audio.' + id, next);
    showBanner('Audio: ' + langName(tracks[next], next) + ' (' + (next + 1) + ' de ' + tracks.length + ')', 2500);
  }

  // ---------- Lista de canales ----------
  var VISIBLE = 13;
  function panelOpen() { return $panel.className.indexOf('hidden') < 0; }
  function openPanel() { focus = current; $banner.className = 'hidden'; $panel.className = ''; renderList(); }
  function closePanel() { $panel.className = 'hidden'; }

  function renderList() {
    if (!panelOpen()) return;
    var start = Math.max(0, Math.min(focus - Math.floor(VISIBLE / 2), CHANNELS.length - VISIBLE));
    var html = '';
    for (var i = start; i < Math.min(CHANNELS.length, start + VISIBLE); i++) {
      var cls = (i === focus ? 'focus ' : '') + (i === current ? 'playing' : '');
      html += '<li class="' + cls + '"><span class="n">' + CHANNELS[i].n + '</span>' + esc(CHANNELS[i].name) + '</li>';
    }
    $list.innerHTML = html;
  }

  // ---------- Número de canal ----------
  function onDigit(d) {
    if (typed.length >= 3) typed = '';
    typed += d;
    showBanner('<span class="num">' + typed + '</span>', 2500);
    clearTimeout(typedTimer);
    if (typed.length === 3) jumpTyped();
    else typedTimer = setTimeout(jumpTyped, 2000);
  }
  function jumpTyped() {
    clearTimeout(typedTimer);
    var n = parseInt(typed, 10);
    typed = '';
    for (var i = 0; i < CHANNELS.length; i++) {
      if (CHANNELS[i].n === n) { closePanel(); play(i); return; }
    }
    showBanner('No existe el canal ' + n, 2000);
  }

  // ---------- Salir ----------
  function exitApp() {
    if (!backArmed) {
      backArmed = true;
      showBanner('Presione Atrás otra vez para salir', 2000);
      setTimeout(function () { backArmed = false; }, 2000);
      return;
    }
    stopPlayer();
    if (isTizen) {
      try { tizen.application.getCurrentApplication().exit(); return; } catch (e) { /* sigue */ }
    }
    if (isWebOS && window.webOS && webOS.platformBack) { webOS.platformBack(); return; }
    window.close();
  }

  // ---------- Control remoto ----------
  document.addEventListener('keydown', function (e) {
    var c = e.keyCode;
    var digit = (c >= 48 && c <= 57) ? c - 48 : (c >= 96 && c <= 105) ? c - 96 : -1;
    var isBack = c === KEY.BACK_TIZEN || c === KEY.BACK_WEBOS || c === KEY.BACKSPACE || c === KEY.ESC;

    if (digit >= 0) { onDigit(digit); e.preventDefault(); return; }

    if (panelOpen()) {
      if (c === KEY.UP) { focus = Math.max(0, focus - 1); renderList(); }
      else if (c === KEY.DOWN) { focus = Math.min(CHANNELS.length - 1, focus + 1); renderList(); }
      else if (c === KEY.PAGE_UP || c === KEY.CH_UP_TIZEN) { focus = Math.max(0, focus - VISIBLE); renderList(); }
      else if (c === KEY.PAGE_DOWN || c === KEY.CH_DOWN_TIZEN) { focus = Math.min(CHANNELS.length - 1, focus + VISIBLE); renderList(); }
      else if (c === KEY.ENTER) { closePanel(); play(focus); }
      else if (isBack) { closePanel(); }
      e.preventDefault();
      return;
    }

    if (c === KEY.UP || c === KEY.CH_UP_TIZEN || c === KEY.PAGE_UP) play(current + 1);
    else if (c === KEY.DOWN || c === KEY.CH_DOWN_TIZEN || c === KEY.PAGE_DOWN) play(current - 1);
    else if (c === KEY.ENTER) { if (typed) jumpTyped(); else openPanel(); }
    else if (c === KEY.MENU_TIZEN || c === KEY.INFO_TIZEN) openPanel();
    else if (c === KEY.LEFT || c === KEY.GREEN) cycleAudio();
    else if (c === KEY.RIGHT || c === KEY.RED) cycleFormat();
    else if (isBack) exitApp();
    else return;
    e.preventDefault();
  });

  // Samsung: pausar al salir a otra app y retomar al volver
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) stopPlayer();
    else play(current);
  });

  // ---------- Inicio ----------
  if (!CHANNELS.length) {
    showStatus('La lista de canales está vacía');
    return;
  }
  var last = getPref('ultimo', '');
  var start = 0;
  for (var s = 0; s < CHANNELS.length; s++) {
    if (CHANNELS[s].id === last) { start = s; break; }
  }
  play(start);
})();
