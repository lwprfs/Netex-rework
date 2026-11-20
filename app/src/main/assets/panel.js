(function () {
  var pendingBlobs = new Map();
  var HLJS_BASE = 'https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles';

  function setTheme(o) {
    var st = document.documentElement.style;
    st.setProperty('--bg', o.bg); st.setProperty('--surface', o.surface); st.setProperty('--surface-low', o.surfaceLow);
    st.setProperty('--fg', o.fg); st.setProperty('--fg-dim', o.fgDim); st.setProperty('--outline', o.outline);
    st.setProperty('--primary', o.primary); st.setProperty('--pc', o.primaryContainer); st.setProperty('--opc', o.onPrimaryContainer);
    st.setProperty('--err', o.error); st.setProperty('--ok', o.ok); st.setProperty('--warn', o.warn); st.setProperty('--rec', o.rec);
    document.getElementById('hljs-theme').href = HLJS_BASE + '/' + (o.dark ? 'github-dark' : 'github') + '.min.css';
  }

  function copyText(text) {
    try { AndroidPanel.postMessage(JSON.stringify({ type: 'copy', text: text })); } catch (e) {}
  }

  function setActiveTab(tab) {
    document.getElementById('console-tab').classList.toggle('active', tab === 'console');
    document.getElementById('source-tab').classList.toggle('active', tab === 'source');
    document.getElementById('network-tab').classList.toggle('active', tab === 'network');
    if (tab === 'console' && !consoleScrolledUp) {
      requestAnimationFrame(function () {
        var entries = document.getElementById('consoleEntries');
        entries.scrollTop = entries.scrollHeight;
      });
    } else if (tab === 'network' && !networkScrolledUp) {
      requestAnimationFrame(function () {
        var l = document.getElementById('netList');
        l.scrollTop = l.scrollHeight;
      });
    }
  }

  var lastSource = '';
  function setSource(html) {
    lastSource = html;
    document.getElementById('sourceInfo').textContent = 'Page source · ' + formatSize(html.length);
    var oldCode = document.querySelector('#source-tab code');
    var newCode = document.createElement('code');
    newCode.className = 'language-html';
    var formatted = html;
    if (typeof html_beautify === 'function') {
      try {
        formatted = html_beautify(html, {
          indent_size: 2,
          indent_with_tabs: false,
          preserve_newlines: true,
          max_preserve_newlines: 1,
          wrap_line_length: 0
        });
      } catch (e) { /* fall back to raw */ }
    }
    newCode.textContent = formatted;
    oldCode.parentNode.replaceChild(newCode, oldCode);
    try { hljs.highlightElement(newCode); } catch (e) {}
  }

  var totalBytes = 0;
  var totalCount = 0;
  var netQuery = '';
  var netList = document.getElementById('netList');
  // Rows live in their own scroll container. Pin to bottom unless the user scrolled up.
  var networkScrolledUp = false;
  netList.addEventListener('scroll', function () {
    networkScrolledUp = (netList.scrollTop + netList.clientHeight) < (netList.scrollHeight - 4);
  }, { passive: true });

  // ======================================================================================
  // Design system helpers: icons, bottom sheets
  // ======================================================================================
  var ICONS = {
    search: 'M15.5 14h-.79l-.28-.27A6.47 6.47 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z',
    more: 'M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z',
    back: 'M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z',
    close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
    sweep: 'M15 16h4v2h-4zm0-8h7v2h-7zm0 4h6v2h-6zM3 18c0 1.1.9 2 2 2h6c1.1 0 2-.9 2-2V8H3v10zM14 5h-3l-1-1H6L5 5H2v2h12z',
    pin: 'M16 9V4h1c.55 0 1-.45 1-1s-.45-1-1-1H7c-.55 0-1 .45-1 1s.45 1 1 1h1v5c0 1.66-1.34 3-3 3v2h5.97v7l1 1 1-1v-7H19v-2c-1.66 0-3-1.34-3-3z',
    copy: 'M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z',
    share: 'M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z',
    rec: 'M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z',
    pause: 'M6 19h4V5H6v14zm8-14v14h4V5h-4z',
    play: 'M8 5v14l11-7z',
    stop: 'M6 6h12v12H6z',
    history: 'M13 3a9 9 0 0 0-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42A8.954 8.954 0 0 0 13 21a9 9 0 0 0 0-18zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z',
    clock: 'M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z',
    multi: 'M19 7v4H5.83l3.58-3.59L8 6l-6 6 6 6 1.41-1.41L5.83 13H21V7z',
    all: 'M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2zm10-6h-2v2h2V3zm6 0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2zm-2-4h2v-2H3v2zM9 3H7v2h2V3zm2 18h2v-2h-2v2zm8-8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2zm0 8h2v-2h-2v2zm-4 4h2v-2h-2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z',
    chart: 'M5 9.2h3V19H5V9.2zM10.6 5h2.8v14h-2.8V5zm5.6 8H19v6h-2.8v-6z',
    shield: 'M12 1 3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4z',
    file: 'M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z',
    list: 'M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z',
    download: 'M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z',
    water: 'M3 4h10v3H3zM7 9h12v3H7zm-2 5h8v3H5z',
    link: 'M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z',
    bolt: 'M7 2v11h3v9l7-12h-4l4-8z',
    eye: 'M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z',
    block: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 1.85 0 3.55.63 4.9 1.69L5.69 16.9A7.902 7.902 0 0 1 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.69L18.31 7.1A7.902 7.902 0 0 1 20 12c0 4.42-3.58 8-8 8z',
    tune: 'M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z'
  };
  function svgIcon(n) { return '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="' + (ICONS[n] || '') + '"/></svg>'; }
  function hydrateIcons(root) {
    (root || document).querySelectorAll('[data-i]').forEach(function (b) {
      if (b.dataset.hyd === '1') return;
      b.dataset.hyd = '1';
      b.insertAdjacentHTML('afterbegin', svgIcon(b.dataset.i));
    });
  }

  var sheetEl = null;
  function closeSheet() {
    if (!sheetEl) return;
    if (sheetEl._restore) sheetEl._restore();
    sheetEl.remove();
    sheetEl = null;
  }
  // One bottom sheet at a time; it closes on a tap outside it.
  function openSheet(title, build) {
    closeSheet();
    var sc = el('div', 'scrim');
    var sh = el('div', 'sheet');
    sh.appendChild(el('div', 'grab'));
    if (title) sh.appendChild(el('h3', null, title));
    sc.appendChild(sh);
    sc.addEventListener('click', function (e) { if (e.target === sc) closeSheet(); });
    build(sh);
    document.body.appendChild(sc);
    sheetEl = sc;
    hydrateIcons(sc);
    return sh;
  }
  function menuItem(icon, label, sub, fn, cls) {
    var b = el('button', 'mi' + (cls ? ' ' + cls : ''));
    var ic = el('span'); ic.innerHTML = svgIcon(icon); b.appendChild(ic.firstChild);
    var tx = el('span', 'tx', label);
    if (sub) tx.appendChild(el('small', null, sub));
    b.appendChild(tx);
    b.addEventListener('click', function () { closeSheet(); fn(); });
    return b;
  }
  function iconButton(icon, label, fn) {
    var b = el('button', 'ib');
    b.setAttribute('aria-label', label);
    b.innerHTML = svgIcon(icon);
    b.addEventListener('click', fn);
    return b;
  }
  function midTruncate(s, max) {
    if (s.length <= max) return s;
    var keep = max - 1, head = Math.ceil(keep * 0.62), tail = keep - head;
    return s.slice(0, head) + '…' + s.slice(s.length - tail);
  }

  function updateTotals() {
    var shown = totalCount;
    if (filtersActive()) shown = netList.querySelectorAll('.req:not(.hide)').length;
    document.getElementById('netTotalsCount').innerHTML = totalCount === 0 ? '' :
      '<b>' + (filtersActive() ? shown + '</b> of ' + totalCount : totalCount + '</b>') + ' request' + (totalCount === 1 ? '' : 's');
    document.getElementById('netTotalsSize').textContent = totalCount === 0 ? '' : formatSize(totalBytes);
  }

  // The Recording button shows the state of the HAR recording; tap opens the Recording page, long press the capture settings.
  function setHarState(state) {
    var btn = document.getElementById('harBtn');
    btn.className = 'ib' + (state === 'idle' ? '' : ' ' + state);
    btn.setAttribute('aria-label', state === 'recording' ? 'Recording in progress' : state === 'paused' ? 'Recording paused' : 'Recording');
  }

  (function () {
    var btn = document.getElementById('harBtn'), timer = null, longPressed = false;
    btn.addEventListener('pointerdown', function () {
      longPressed = false;
      timer = setTimeout(function () {
        longPressed = true;
        try { AndroidPanel.postMessage(JSON.stringify({ type: 'har-menu' })); } catch (e) {}
      }, 550);
    });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach(function (ev) {
      btn.addEventListener(ev, function () { clearTimeout(timer); });
    });
    btn.addEventListener('click', function () {
      if (longPressed) { longPressed = false; return; }
      openPage('recording');
    });
  })();

  function typeOf(r) {
    var ct = (r.contentType || '').toLowerCase(), u = r.url || '';
    if (/^image\//.test(ct) || /\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)(\?|#|$)/i.test(u)) return 'img';
    if (/^(video|audio)\//.test(ct) || /\.(mp4|webm|m4a|mp3|ogg|wav|m3u8|mpd)(\?|#|$)/i.test(u)) return 'media';
    if (/^font\//.test(ct) || ct.indexOf('woff') !== -1 || /\.(woff2?|ttf|otf|eot)(\?|#|$)/i.test(u)) return 'font';
    if (ct.indexOf('wasm') !== -1 || /\.wasm(\?|#|$)/i.test(u)) return 'wasm';
    if (ct.indexOf('html') !== -1) return 'doc';
    if (ct.indexOf('javascript') !== -1 || ct.indexOf('ecmascript') !== -1 || /\.m?js(\?|#|$)/i.test(u)) return 'js';
    if (ct.indexOf('css') !== -1 || /\.css(\?|#|$)/i.test(u)) return 'css';
    if (ct.indexOf('json') !== -1 || ct.indexOf('xml') !== -1 || ct.indexOf('text/') === 0) return 'data';
    return 'other';
  }

  // ======================================================================================
  // Network: filters, request list
  // ======================================================================================
  var netTerms = [];
  // Primary chips are shortcuts for the type set; the "More" sheet edits the same sets.
  var filt = { types: new Set(), methods: new Set(), statuses: new Set() };
  var SHORTCUTS = { all: [], fetch: ['fetch', 'xhr'], doc: ['doc'] };

  function sameSet(set, arr) {
    if (set.size !== arr.length) return false;
    for (var i = 0; i < arr.length; i++) if (!set.has(arr[i])) return false;
    return true;
  }
  function primaryOf() {
    for (var k in SHORTCUTS) if (sameSet(filt.types, SHORTCUTS[k])) return k;
    return null;
  }
  function typeMatches(d, key) {
    switch (key) {
      case 'fetch': case 'xhr': return d.kind === key;
      case 'failed': return d.failed === '1';
      case 'nav': return d.isnav === '1';
      case 'slow': return d.slow === '1';
      default: return d.type === key;
    }
  }
  function statusMatches(d, key) {
    if (key === 'failed') return d.failed === '1';
    if (key === 'blocked') return d.blocked === '1';
    return d.sc === key;
  }
  function filtersActive() {
    return filt.types.size > 0 || filt.methods.size > 0 || filt.statuses.size > 0 || netTerms.length > 0;
  }
  function secondaryCount() {
    var n = filt.methods.size + filt.statuses.size;
    if (primaryOf() === null) n += filt.types.size;
    return n;
  }

  function applyNetFilter(det) {
    var d = det.dataset, ok = true, hit, k;
    if (filt.types.size) {
      hit = false;
      filt.types.forEach(function (t) { if (typeMatches(d, t)) hit = true; });
      ok = hit;
    }
    if (ok && filt.methods.size && !filt.methods.has(d.method)) ok = false;
    if (ok && filt.statuses.size) {
      hit = false;
      filt.statuses.forEach(function (s) { if (statusMatches(d, s)) hit = true; });
      ok = hit;
    }
    // Every space-separated word must appear in "method status url type content-type".
    if (ok) {
      var hay = d.hay || '';
      for (var i = 0; i < netTerms.length; i++) if (hay.indexOf(netTerms[i]) === -1) { ok = false; break; }
    }
    det.classList.toggle('hide', !ok);
  }

  function refreshFilterUi() {
    var p = primaryOf();
    document.querySelectorAll('#netChips .seg[data-p]').forEach(function (c) {
      var key = c.dataset.p;
      if (key === 'more') c.classList.toggle('on', secondaryCount() > 0);
      else c.classList.toggle('on', p === key);
    });
    var n = secondaryCount();
    var badge = document.getElementById('nMoreBadge');
    badge.textContent = n ? String(n) : '';
    document.getElementById('nReset').hidden = !filtersActive();
    updateTotals();
  }

  function refilterNetwork() {
    netList.querySelectorAll('.req').forEach(applyNetFilter);
    refreshFilterUi();
  }

  function resetFilters() {
    filt.types.clear(); filt.methods.clear(); filt.statuses.clear();
    netTerms = []; netQuery = '';
    document.getElementById('netSearch').value = '';
    refilterNetwork();
  }

  document.getElementById('netChips').addEventListener('click', function (e) {
    var chip = e.target.closest('.seg');
    if (!chip) return;
    var key = chip.dataset.p;
    if (key === 'more') { openMoreFilters(); return; }
    filt.types = new Set(SHORTCUTS[key]);
    refilterNetwork();
  });
  document.getElementById('nReset').addEventListener('click', resetFilters);
  document.getElementById('netSearch').addEventListener('input', function () {
    netQuery = this.value.trim().toLowerCase();
    netTerms = netQuery ? netQuery.split(/\s+/) : [];
    refilterNetwork();
  });
  document.getElementById('nSearchBtn').addEventListener('click', function () {
    var row = document.getElementById('nSearchRow');
    row.hidden = !row.hidden;
    this.classList.toggle('on', !row.hidden);
    if (!row.hidden) document.getElementById('netSearch').focus();
    else if (netQuery) { document.getElementById('netSearch').value = ''; netQuery = ''; netTerms = []; refilterNetwork(); }
  });

  // Secondary filters: one sheet, several selections allowed in each group.
  function openMoreFilters() {
    openSheet('Filters', function (sh) {
      function group(title, items, set, labelOf) {
        sh.appendChild(el('h4', null, title));
        var box = el('div', 'opts');
        items.forEach(function (it) {
          var b = el('button', 'opt' + (set.has(it[0]) ? ' on' : ''), it[1]);
          b.addEventListener('click', function () {
            if (set.has(it[0])) set.delete(it[0]); else set.add(it[0]);
            b.classList.toggle('on', set.has(it[0]));
            refilterNetwork();
          });
          box.appendChild(b);
        });
        sh.appendChild(box);
      }
      group('Resource types', [
        ['js', 'JavaScript'], ['css', 'CSS'], ['img', 'Images'], ['media', 'Media'], ['font', 'Fonts'],
        ['data', 'Data'], ['wasm', 'WebAssembly'], ['other', 'Other'], ['failed', 'Failed'], ['nav', 'Navigation'],
        ['xhr', 'XHR'], ['fetch', 'Fetch'], ['doc', 'Documents'], ['slow', 'Slow (>1 s)']
      ], filt.types);
      group('HTTP methods', [['GET', 'GET'], ['POST', 'POST'], ['PUT', 'PUT'], ['PATCH', 'PATCH'], ['DELETE', 'DELETE'], ['OPTIONS', 'OPTIONS']], filt.methods);
      group('HTTP status', [['2', '2xx'], ['3', '3xx'], ['4', '4xx'], ['5', '5xx'], ['failed', 'Failed'], ['blocked', 'Blocked']], filt.statuses);
      var row = el('div', 'row2');
      var reset = el('button', 'btn', 'Reset filters');
      reset.addEventListener('click', function () { resetFilters(); closeSheet(); });
      var done = el('button', 'btn pri', 'Done');
      done.addEventListener('click', closeSheet);
      row.appendChild(reset); row.appendChild(done);
      sh.appendChild(row);
    });
  }

  function showEmptyNetwork() {
    netList.replaceChildren();
    var d = document.createElement('div');
    d.className = 'empty';
    d.innerHTML = '<b>No requests yet</b>Load or reload a page to see its network traffic.';
    netList.appendChild(d);
  }

  function clearNetwork() {
    showEmptyNetwork();
    totalBytes = 0;
    totalCount = 0;
    networkScrolledUp = false;
    closeInspector();
    exitSelection();
    updateTotals();
  }

  function appendNetworkRow(r) {
    var container = netList;
    var det = r.requestId
      ? container.querySelector('.req[data-rid="' + r.requestId + '"]')
      : null;
    var isNew = !det;

    if (isNew) {
      var empty = container.querySelector('.empty');
      if (empty) empty.remove();
      det = document.createElement('div');
      det.className = 'req';
      if (r.requestId) det.dataset.rid = r.requestId;
      det.dataset.lastSize = '0';
      container.appendChild(det);
      totalCount++;
    }

    // Track size delta per row so progress ticks update totals without double-counting.
    var lastSize = parseInt(det.dataset.lastSize, 10) || 0;
    var newSize = r.size || 0;
    totalBytes += newSize - lastSize;
    det.dataset.lastSize = String(newSize);
    updateTotals();

    renderRow(det, r);
    // A selected request keeps its inspector current while progress ticks arrive.
    if (inspected === det) renderInspector();

    if (isNew) trimRows();
    if (isNew && !networkScrolledUp) netList.scrollTop = netList.scrollHeight;
  }

  function isBlocked(r) {
    return /^Blocked by Netex/.test(r.body || '') || /^Blocked/i.test(r.error || '');
  }

  function stateOf(r) {
    if (r.pending) return 's-pending';
    if (isBlocked(r)) return 's-blocked';
    if (r.failed) return 's-fail';
    if (r.redirectHop) return 's-redir';
    if (r.status === 0) return r.capture === 'native' ? 's-na' : 's-pending';
    if (r.status < 300) return 's-ok';
    if (r.status < 400) return 's-redir';
    if (r.status < 500) return 's-c4';
    return 's-c5';
  }

  function statusLabel(r) {
    if (r.pending) return ['…', 'pending'];
    if (isBlocked(r)) return ['BLK', 'blocked'];
    if (r.failed) return ['ERR', 'failed'];
    if (r.redirectHop) return ['→', 'redirect hop'];
    if (r.status === 0) return ['—', r.capture === 'native' ? 'status n/a' : 'no response'];
    return [String(r.status), r.status < 300 ? 'OK' : r.status < 400 ? 'redirect' : r.status < 500 ? 'client error' : 'server error'];
  }

  function splitUrl(u) {
    try {
      var x = new URL(u);
      var path = (x.pathname === '/' && !x.search ? '/' : x.pathname) + x.search;
      return { host: x.host, path: path || '/' };
    } catch (e) { return { host: '', path: u }; }
  }

  function renderRow(det, r) {
    var type = typeOf(r);
    var parts = splitUrl(r.url || '');
    det.dataset.type = type;
    det.dataset.url = r.url || '';
    det.dataset.blocked = isBlocked(r) ? '1' : '0';
    det.dataset.err = (!r.pending && (r.failed || r.status >= 400 || (r.status === 0 && r.capture !== 'native'))) ? '1' : '0';
    det.dataset.nav = String(r.navId || 0);
    det.dataset.isnav = (r.kind === 'document' && r.navId) ? '1' : '0';
    det.dataset.slow = (!r.pending && r.durationMs > 1000) ? '1' : '0';
    det.dataset.write = ['POST', 'PUT', 'PATCH', 'DELETE'].indexOf((r.method || '').toUpperCase()) !== -1 ? '1' : '0';
    det._r = r;
    det.dataset.kind = r.kind || '';
    det.dataset.method = (r.method || '').toUpperCase();
    det.dataset.sc = (!r.pending && r.status >= 200) ? String(Math.floor(r.status / 100)) : '';
    det.dataset.failed = (r.failed || (!r.pending && r.status === 0 && r.capture !== 'native')) ? '1' : '0';
    det.dataset.hay = ((r.method || '') + ' ' + (r.pending ? '' : r.status) + ' ' + (r.url || '') + ' ' + type + ' ' + (r.contentType || '')).toLowerCase();

    var st = stateOf(r), lab = statusLabel(r);
    det.className = 'req ' + st + (det.classList.contains('old') ? ' old' : '') + (det.classList.contains('sel') ? ' sel' : '') + (det === inspected ? ' cur' : '');

    var bar = el('span', 'rbar');
    var chk = el('span', 'chk', det.classList.contains('sel') ? '✓' : '');
    var main = el('div', 'rmain');
    var l1 = el('div', 'l1');
    l1.appendChild(span('method ' + r.method, r.method));
    l1.appendChild(span('path', midTruncate(r.displayPath || parts.path, 90)));
    var l2 = el('div', 'l2');
    l2.appendChild(span('host', parts.host || r.displayUrl || ''));
    var bits = [typeLabel(type, r)];
    if (r.size) bits.push(formatSize(r.size));
    if (r.durationMs) bits.push(r.durationMs + ' ms');
    bits.forEach(function (b) { l2.appendChild(span('dotsep', b)); });
    main.appendChild(l1);
    main.appendChild(l2);
    var rs = el('div', 'rst');
    rs.appendChild(el('b', null, lab[0]));
    rs.appendChild(el('small', null, lab[1]));
    det.replaceChildren(bar, chk, main, rs);
    applyNetFilter(det);
  }

  function typeLabel(type, r) {
    if (r.kind === 'fetch' || r.kind === 'xhr') return r.kind === 'xhr' ? 'XHR' : 'Fetch';
    return { doc: 'Document', js: 'Script', css: 'Style', img: 'Image', media: 'Media', font: 'Font', wasm: 'Wasm', data: 'Data', other: 'Other' }[type] || type;
  }

  function shellQuote(str) {
    return "'" + String(str).replace(/'/g, "'\\''") + "'";
  }

  function toCurl(r, hideSecrets) {
    var method = (r.method || 'GET').toUpperCase();
    var out = 'curl ' + shellQuote(r.url);
    if (method !== 'GET') out += ' -X ' + method;
    (hideSecrets ? redact(r.reqHeaders) : (r.reqHeaders || [])).forEach(function (h) {
      if (h[0] && h[0].charAt(0) !== ':') out += ' -H ' + shellQuote(h[0] + ': ' + h[1]);
    });
    if (r.reqBody) out += ' --data-raw ' + shellQuote(r.reqBody);
    return out;
  }

  // ======================================================================================
  // Shared helpers
  // ======================================================================================
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined && text !== null) e.textContent = text;
    return e;
  }

  function shareText(title, text) {
    try { AndroidPanel.postMessage(JSON.stringify({ type: 'share', title: title, text: text })); } catch (e) {}
  }

  function savePref(key, value) {
    try { AndroidPanel.postMessage(JSON.stringify({ type: 'pref', key: key, value: String(value) })); } catch (e) {}
  }

  function hms(ms) {
    var d = new Date(ms);
    function p(n, w) { n = String(n); while (n.length < w) n = '0' + n; return n; }
    return p(d.getHours(), 2) + ':' + p(d.getMinutes(), 2) + ':' + p(d.getSeconds(), 2) + '.' + p(d.getMilliseconds(), 3);
  }

  function tryJson(text) {
    var t = String(text || '').trim();
    if (!t || (t[0] !== '{' && t[0] !== '[')) return { ok: false };
    try { return { ok: true, value: JSON.parse(t) }; } catch (e) { return { ok: false }; }
  }

  function valueText(v) {
    return (v !== null && typeof v === 'object') ? JSON.stringify(v, null, 2) : String(v);
  }

  function hostOf(u) {
    try { return new URL(u).host; } catch (e) { return u; }
  }

  // ======================================================================================
  // JSON tree viewer (console objects + network payload / response)
  // ======================================================================================
  function jsonLeaf(v) {
    var cls = v === null ? 'jnull' : typeof v === 'string' ? 'jstr' : typeof v === 'number' ? 'jnum' : 'jbool';
    var text = typeof v === 'string' ? JSON.stringify(v) : String(v);
    if (text.length > 400) text = text.slice(0, 400) + '…';
    return el('span', cls, text);
  }

  function attachCopy(node, value) {
    node.addEventListener('contextmenu', function (e) {
      e.preventDefault();
      e.stopPropagation();
      copyText(valueText(value));
    });
  }

  function jsonNode(key, val, depth, openDepth) {
    var isObj = val !== null && typeof val === 'object';
    var keyEl = key === null ? null : el('span', 'jkey', key + ': ');
    if (!isObj) {
      var row = el('div', 'jn');
      if (keyEl) row.appendChild(keyEl);
      row.appendChild(jsonLeaf(val));
      attachCopy(row, val);
      return row;
    }
    var isArr = Array.isArray(val);
    var keys = Object.keys(val);
    var det = el('details', 'jd');
    var sum = el('summary');
    if (keyEl) sum.appendChild(keyEl);
    var count = keys.length + (isArr ? (keys.length === 1 ? ' item' : ' items') : (keys.length === 1 ? ' key' : ' keys'));
    sum.appendChild(el('span', 'jdim', (isArr ? '[' : '{') + (keys.length ? '…' : '') + (isArr ? ']' : '}') + ' ' + count));
    attachCopy(sum, val);
    det.appendChild(sum);
    var built = false;
    function build() {
      if (built) return;
      built = true;
      var shown = Math.min(keys.length, 300);
      for (var i = 0; i < shown; i++) {
        det.appendChild(jsonNode(isArr ? '[' + keys[i] + ']' : keys[i], val[keys[i]], depth + 1, openDepth));
      }
      if (keys.length > shown) det.appendChild(el('div', 'jn jdim', '… ' + (keys.length - shown) + ' more'));
    }
    det.addEventListener('toggle', function () { if (det.open) build(); });
    if (depth < openDepth) { det.open = true; build(); }
    return det;
  }

  // Paths whose key or value contains the query (searching a lazy tree without expanding it).
  function jsonSearch(root, q) {
    var out = [];
    var seen = {};
    (function walk(val, path) {
      if (out.length >= 200) return;
      if (val !== null && typeof val === 'object') {
        Object.keys(val).forEach(function (k) {
          var p = Array.isArray(val) ? path + '[' + k + ']' : (path ? path + '.' + k : k);
          var child = val[k];
          if (k.toLowerCase().indexOf(q) !== -1 && (child === null || typeof child !== 'object') && !seen[p]) {
            seen[p] = 1;
            out.push([p, child]);
          }
          walk(child, p);
        });
      } else if (String(val).toLowerCase().indexOf(q) !== -1 && !seen[path]) {
        seen[path] = 1;
        out.push([path, val]);
      }
    })(root, '');
    return out;
  }

  function jsonViewer(value) {
    var box = el('div', 'jv');
    var bar = el('div', 'jbar');
    var input = el('input');
    input.type = 'search';
    input.placeholder = 'Search keys and values';
    input.setAttribute('autocomplete', 'off');
    input.setAttribute('autocapitalize', 'off');
    var copyAll = el('button', null, 'Copy JSON');
    copyAll.addEventListener('click', function (e) { e.stopPropagation(); copyText(JSON.stringify(value, null, 2)); });
    bar.appendChild(input);
    bar.appendChild(copyAll);
    var tree = el('div', 'jt');
    tree.appendChild(jsonNode(null, value, 0, 1));
    var results = el('div', 'jt');
    results.style.display = 'none';
    input.addEventListener('input', function () {
      var q = input.value.trim().toLowerCase();
      if (!q) { results.style.display = 'none'; tree.style.display = ''; return; }
      var hits = jsonSearch(value, q);
      results.replaceChildren();
      if (!hits.length) results.appendChild(el('div', 'jn jdim', 'No matches'));
      hits.forEach(function (h) {
        var row = el('div', 'jn');
        row.appendChild(el('span', 'jkey', h[0] + ': '));
        row.appendChild(jsonLeaf(h[1]));
        attachCopy(row, h[1]);
        results.appendChild(row);
      });
      tree.style.display = 'none';
      results.style.display = '';
    });
    box.appendChild(bar);
    box.appendChild(tree);
    box.appendChild(results);
    box.appendChild(el('div', 'jhint', 'Tap to expand · long-press a value to copy it'));
    return box;
  }

  // ======================================================================================
  // Network: request details
  // ======================================================================================
  var SENSITIVE = /^(authorization|proxy-authorization|cookie|set-cookie|x-api-key|x-auth-token|x-csrf-token|x-xsrf-token)$/i;

  function redact(list) {
    return (list || []).map(function (h) { return SENSITIVE.test(h[0]) ? [h[0], '[redacted]'] : h; });
  }

  function headersText(list) {
    return (list || []).map(function (h) { return h[0] + ': ' + h[1]; }).join('\n');
  }

  // Text for sharing: sensitive headers (cookies, tokens) are redacted.
  function requestText(r) {
    var out = r.method + ' ' + r.url + '\n';
    out += 'Status: ' + (r.pending ? 'pending' : r.status) + (r.durationMs ? ' · ' + r.durationMs + ' ms' : '') + (r.size ? ' · ' + formatSize(r.size) : '') + '\n';
    var rq = redact(r.reqHeaders), rs = redact(r.resHeaders);
    if (rq.length) out += '\nRequest headers\n' + headersText(rq) + '\n';
    if (r.reqBody) out += '\nRequest payload\n' + r.reqBody + '\n';
    if (rs.length) out += '\nResponse headers\n' + headersText(rs) + '\n';
    out += '\ncURL (sensitive headers redacted)\n' + toCurl(r, true) + '\n';
    return out;
  }

  function recordJson(r) {
    return {
      method: r.method, url: r.url, status: r.status, contentType: r.contentType || '',
      durationMs: r.durationMs || 0, size: r.size || 0, kind: r.kind || '', startedAt: r.startedAt || 0,
      requestHeaders: redact(r.reqHeaders), requestBody: r.reqBody || '',
      responseHeaders: redact(r.resHeaders), responseBody: (r.body || '').slice(0, 100000),
      timings: r.timings || null
    };
  }

  function actionButton(label, fn) {
    var b = el('button', null, label);
    b.addEventListener('click', function (e) { e.stopPropagation(); fn(); });
    return b;
  }

  function postBlock(pattern) {
    try { AndroidPanel.postMessage(JSON.stringify({ type: 'block', pattern: pattern })); } catch (e) {}
  }

  function section(title, builder, open) {
    var d = el('details', 'sec');
    d.appendChild(el('summary', null, title));
    var built = false;
    function build() {
      if (built) return;
      built = true;
      d.appendChild(builder());
    }
    d.addEventListener('toggle', function () { if (d.open) build(); });
    if (open) { d.open = true; build(); }
    return d;
  }

  function kvTable(pairs, emptyText) {
    var t = el('div', 'kv');
    if (!pairs || !pairs.length) { t.appendChild(el('div', 'jdim', emptyText)); return t; }
    pairs.forEach(function (p) {
      var row = el('div', 'kvrow');
      row.appendChild(el('span', 'kvk', p[0]));
      row.appendChild(el('span', 'kvv', p[1]));
      row.addEventListener('contextmenu', function (e) { e.preventDefault(); copyText(p[1]); });
      t.appendChild(row);
    });
    return t;
  }

  function queryPairs(u) {
    var out = [];
    try { new URL(u).searchParams.forEach(function (v, k) { out.push([k, v]); }); } catch (e) {}
    return out;
  }

  // ======================================================================================
  // Network: overlays, waterfall, edit & replay
  // ======================================================================================
  function overlay(title) {
    var ov = el('div', 'ov');
    var head = el('div', 'ovhead');
    head.appendChild(iconButton('back', 'Back', function () { ov.remove(); }));
    head.appendChild(el('b', null, title));
    head.appendChild(el('span', 'spacer'));
    var body = el('div', 'ovbody');
    ov.appendChild(head);
    ov.appendChild(body);
    document.getElementById('network-tab').appendChild(ov);
    return { el: ov, body: body };
  }

  function visibleRecords() {
    var out = [];
    netList.querySelectorAll('div.req').forEach(function (d) {
      if (d._r && !d.classList.contains('hide')) out.push(d._r);
    });
    return out;
  }

  function openWaterfall() {
    var items = visibleRecords().filter(function (r) { return r.startedAt; });
    var ov = overlay('Waterfall');
    if (!items.length) {
      ov.body.appendChild(el('div', 'empty', 'No timed requests to show. Requests need a start time reported by the page.'));
      return;
    }
    items.sort(function (a, b) { return a.startedAt - b.startedAt; });
    var t0 = items[0].startedAt, t1 = t0;
    items.forEach(function (r) { t1 = Math.max(t1, r.startedAt + (r.durationMs || 0)); });
    var span = Math.max(1, t1 - t0);
    ov.body.appendChild(el('div', 'note', items.length + ' requests over ' + span + ' ms. Bars use only the start time and duration the page reported.'));
    items.forEach(function (r) {
      var row = el('div', 'wf-row');
      row.appendChild(el('span', 'wf-label', r.method + ' ' + (r.displayUrl || r.url)));
      var track = el('div', 'wf-track');
      var bar = el('div', 'wf-bar' + ((!r.pending && (r.status >= 400 || r.status === 0)) ? ' e' : (r.durationMs > 1000 ? ' w' : '')));
      bar.style.left = ((r.startedAt - t0) / span * 100) + '%';
      bar.style.width = Math.max(0.5, (r.durationMs || 0) / span * 100) + '%';
      track.appendChild(bar);
      row.appendChild(track);
      row.appendChild(el('span', 'wf-ms', r.durationMs ? r.durationMs + ' ms' : '…'));
      ov.body.appendChild(row);
    });
  }

  var replayCallbacks = {};
  var replaySeq = 0;

  function parseHeaderLines(text) {
    var out = [];
    text.split('\n').forEach(function (line) {
      var i = line.indexOf(':');
      if (i < 1) return;
      var k = line.slice(0, i).trim(), v = line.slice(i + 1).trim();
      if (k) out.push([k, v]);
    });
    return out;
  }

  function openReplay(r) {
    var ov = overlay('Edit & replay');
    var b = ov.body;
    b.classList.add('form');

    var methodSel = el('select');
    ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'].forEach(function (m) {
      var o = el('option', null, m); o.value = m; methodSel.appendChild(o);
    });
    methodSel.value = (r.method || 'GET').toUpperCase();
    var urlIn = el('input');
    urlIn.type = 'text';
    urlIn.value = r.url;
    urlIn.setAttribute('autocapitalize', 'off');
    urlIn.setAttribute('spellcheck', 'false');

    var shown = [], hidden = [];
    (r.reqHeaders || []).forEach(function (h) {
      if (!h[0] || h[0].charAt(0) === ':' || /^(host|content-length|connection)$/i.test(h[0])) return;
      (SENSITIVE.test(h[0]) ? hidden : shown).push(h[0] + ': ' + h[1]);
    });
    var headersIn = el('textarea');
    headersIn.value = shown.join('\n');
    headersIn.setAttribute('spellcheck', 'false');
    var bodyIn = el('textarea');
    bodyIn.value = r.reqBody || '';
    bodyIn.setAttribute('spellcheck', 'false');

    b.appendChild(el('label', null, 'Method')); b.appendChild(methodSel);
    b.appendChild(el('label', null, 'URL (edit query parameters here)')); b.appendChild(urlIn);
    b.appendChild(el('label', null, 'Headers (one “Name: value” per line)')); b.appendChild(headersIn);
    b.appendChild(el('label', null, 'Body')); b.appendChild(bodyIn);

    var sens = el('input'); sens.type = 'checkbox';
    var sensRow = el('label', 'chk'); sensRow.appendChild(sens);
    sensRow.appendChild(document.createTextNode('Include ' + hidden.length + ' sensitive header' + (hidden.length === 1 ? '' : 's') + ' (Authorization, Cookie, tokens)'));
    if (hidden.length) {
      b.appendChild(sensRow);
      sens.addEventListener('change', function () {
        var lines = headersIn.value.split('\n').filter(function (l) { return hidden.indexOf(l) === -1; });
        if (sens.checked) lines = lines.concat(hidden);
        headersIn.value = lines.filter(function (l) { return l.length; }).join('\n');
      });
    }
    var cookies = el('input'); cookies.type = 'checkbox';
    var cookieRow = el('label', 'chk'); cookieRow.appendChild(cookies);
    cookieRow.appendChild(document.createTextNode('Send this site’s stored cookies'));
    b.appendChild(cookieRow);

    var send = el('button', 'go', 'Send');
    b.appendChild(send);
    b.appendChild(el('div', 'note', 'Sent from the app itself, so the page’s CORS rules don’t apply. Cookies and sensitive headers are off unless you tick them. PATCH is sent as POST with X-HTTP-Method-Override.'));
    var out = el('div');
    b.appendChild(out);

    send.addEventListener('click', function () {
      var id = 'rp-' + (++replaySeq);
      out.replaceChildren(el('div', 'note', 'Sending…'));
      replayCallbacks[id] = function (res) {
        out.replaceChildren();
        if (!res.ok) { out.appendChild(el('div', 'note err', 'Failed: ' + res.error + (res.ms ? ' (' + res.ms + ' ms)' : ''))); return; }
        out.appendChild(el('div', 'note', 'HTTP ' + res.status + ' ' + (res.statusText || '') + ' · ' + res.ms + ' ms · ' + formatSize(res.size || 0) + (res.truncated ? ' (truncated)' : '')));
        if (res.headers && res.headers.length) {
          out.appendChild(section('Response headers (' + res.headers.length + ')', function () { return kvTable(res.headers, ''); }));
        }
        out.appendChild(section('Response', function () {
          if (res.binary) return el('div', 'pad jdim', 'Binary response (' + (res.contentType || 'unknown type') + ')');
          if (!res.body) return el('div', 'pad jdim', 'Empty response');
          var j = tryJson(res.body);
          if (j.ok) return jsonViewer(j.value);
          var pre = el('pre'); pre.textContent = res.body; return pre;
        }, true));
        var copyBtn = el('button', 'prevbtn', 'Copy response');
        copyBtn.addEventListener('click', function () { copyText(res.body || ''); });
        out.appendChild(copyBtn);
      };
      try {
        AndroidPanel.postMessage(JSON.stringify({
          type: 'replay', id: id, method: methodSel.value, url: urlIn.value.trim(),
          headers: parseHeaderLines(headersIn.value), body: bodyIn.value, cookies: cookies.checked
        }));
      } catch (e) {
        out.replaceChildren(el('div', 'note err', 'Could not send: ' + e));
      }
    });
  }

  function deliverReplay(id, resultJson) {
    var cb = replayCallbacks[id];
    if (!cb) return;
    delete replayCallbacks[id];
    var res;
    try { res = JSON.parse(resultJson); } catch (e) { res = { ok: false, error: 'Unreadable result' }; }
    cb(res);
  }

  // Toolbar buttons
  var keepNetwork = false;
  document.getElementById('nKeep').addEventListener('click', function () {
    keepNetwork = !keepNetwork;
    this.classList.toggle('on', keepNetwork);
    devtools('set-preserve', { on: keepNetwork });
  });

  function buildMedia(r) {
    if (isImage(r)) {
      var img = document.createElement('img');
      img.loading = 'lazy';
      img.referrerPolicy = 'no-referrer';
      img.alt = '';
      if (!r.requestId) img.src = r.url;
      return img;
    }
    if (isModel(r)) {
      var mv = document.createElement('model-viewer');
      mv.setAttribute('camera-controls', '');
      mv.setAttribute('auto-rotate', '');
      mv.setAttribute('touch-action', 'pan-y');
      if (!r.requestId) mv.setAttribute('src', r.url);
      return mv;
    }
    return null;
  }

  function buildText(r) {
    var pre = document.createElement('pre');
    var code = document.createElement('code');
    var ct = (r.contentType || '').toLowerCase();
    var raw = r.body || '';
    if (ct.indexOf('json') !== -1) {
      code.className = 'language-json';
      code.textContent = prettyJson(raw);
    } else {
      code.className = 'language-plaintext';
      code.textContent = raw;
    }
    pre.appendChild(code);
    try { hljs.highlightElement(code); } catch (e) {}
    return pre;
  }

  function prettyJson(raw) {
    var trimmed = raw.replace(/^\s+/, '');
    if (trimmed[0] !== '{' && trimmed[0] !== '[') return raw;
    try { return JSON.stringify(JSON.parse(raw), null, 2); } catch (e) { return raw; }
  }

  function isImage(r) {
    if (/^image\//i.test(r.contentType || '')) return true;
    return /\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)(\?|#|$)/i.test(r.url);
  }
  function isModel(r) {
    if (/^model\/gltf/i.test(r.contentType || '')) return true;
    return /\.(glb|gltf)(\?|#|$)/i.test(r.url);
  }

  function formatSize(b) {
    if (b < 1024) return b + ' B';
    if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB';
    return (b / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function span(cls, text) {
    var e = document.createElement('span');
    e.className = cls;
    e.textContent = text;
    return e;
  }

  function deliverBlob(rid, dataUrl) {
    var pending = pendingBlobs.get(rid);
    if (!pending) return;
    pending.forEach(function (cb) { cb(dataUrl); });
    pendingBlobs.delete(rid);
  }

  function requestBlob(rid, cb) {
    var pending = pendingBlobs.get(rid);
    if (!pending) {
      pending = [];
      pendingBlobs.set(rid, pending);
      try {
        AndroidPanel.postMessage(JSON.stringify({ type: 'fetch-blob', requestId: rid }));
      } catch (e) {
        cb('');
        return;
      }
    }
    pending.push(cb);
  }

  var MAX_CONSOLE_ENTRIES = 1000;
  var MAX_HISTORY = 100;
  var pendingEvals = new Set();
  var evalSeq = 0;
  var history = [];
  var historyCursor = -1;
  var draft = '';
  var consoleLogCount = 0;
  var consoleWarnCount = 0;
  var consoleErrorCount = 0;
  // Track via scroll-event rather than reading scrollTop on every append; per-log
  // layout flush gets expensive when chatty pages spam console.log.
  var consoleScrolledUp = false;

  var GLYPH = { input: '❯', result: '❮', result_error: '❮', warn: '⚠', error: '✕' };

  // CSS allowlist for %c styled segments — anything not on this list is dropped so a
  // page can't escape the chip with e.g. position:fixed or background:url(...).
  var SAFE_CSS = new Set([
    'color', 'background', 'background-color',
    'font', 'font-size', 'font-weight', 'font-family', 'font-style',
    'padding', 'padding-left', 'padding-right', 'padding-top', 'padding-bottom',
    'margin', 'margin-left', 'margin-right', 'margin-top', 'margin-bottom',
    'border', 'border-radius', 'border-color', 'border-style', 'border-width',
    'text-decoration', 'text-shadow', 'text-transform',
    'line-height', 'letter-spacing', 'word-spacing'
  ]);

  function sanitizeStyle(s) {
    var parts = String(s).split(';');
    var out = [];
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i].trim();
      if (!p) continue;
      var colon = p.indexOf(':');
      if (colon === -1) continue;
      var key = p.slice(0, colon).trim().toLowerCase();
      var val = p.slice(colon + 1).trim();
      if (/url\(|expression\(|@import|javascript:/i.test(val)) continue;
      if (SAFE_CSS.has(key)) out.push(key + ':' + val);
    }
    return out.join(';');
  }

  var consoleFilter = 'all';
  var consoleQuery = '';

  function levelMatches(level) {
    switch (consoleFilter) {
      case 'log': return level === 'log';
      case 'info': return level === 'info';
      case 'debug': return level === 'debug';
      case 'warn': return level === 'warn';
      case 'error': return level === 'error' || level === 'result_error';
      default: return true;
    }
  }

  function applyConsoleFilter(div) {
    if (!div._level) return; // navigation separators always stay
    var ok = levelMatches(div._level) &&
      (!consoleQuery || (div._text || '').toLowerCase().indexOf(consoleQuery) !== -1);
    div.classList.toggle('hide', !ok);
  }

  function refilterConsole() {
    var kids = document.getElementById('consoleEntries').children;
    for (var i = 0; i < kids.length; i++) applyConsoleFilter(kids[i]);
  }

  // JSON-looking messages become an expandable tree instead of one long line.
  function jsonPreview(text) {
    var j = tryJson(text);
    if (!j.ok || text.trim().length < 3) return null;
    var node = jsonNode(null, j.value, 0, 0);
    var sm = node.querySelector ? node.querySelector('summary') : null;
    if (sm) sm.appendChild(el('span', 'jprev', ' ' + text.replace(/\s+/g, ' ').slice(0, 80)));
    return node;
  }

  function appendConsoleEntry(r) {
    var entries = document.getElementById('consoleEntries');
    var level = r.level || 'log';
    var div = document.createElement('div');
    div.className = 'entry ' + level;
    div._level = level;
    div._time = Date.now();
    div._src = r.sourceId || '';
    div._line = r.line || 0;

    var glyph = document.createElement('span');
    glyph.className = 'glyph';
    glyph.textContent = GLYPH[level] || '';
    div.appendChild(glyph);
    div.appendChild(el('span', 'ts', hms(div._time)));

    var msg = document.createElement('span');
    msg.className = 'msg';
    var text = '';
    if (Array.isArray(r.segments)) {
      var single = r.segments.length === 1 && !r.segments[0].style;
      var tree = single ? jsonPreview(r.segments[0].text || '') : null;
      if (tree) {
        text = r.segments[0].text || '';
        msg.appendChild(tree);
      } else {
        r.segments.forEach(function (s) {
          var seg = document.createElement('span');
          seg.textContent = s.text || '';
          text += s.text || '';
          if (s.style) seg.style.cssText = sanitizeStyle(s.style);
          msg.appendChild(seg);
        });
      }
    } else {
      text = r.message || '';
      var t2 = (level === 'result') ? jsonPreview(text) : null;
      if (t2) msg.appendChild(t2); else msg.textContent = text;
    }
    // Long stack traces fold under their first line.
    var stackAt = text.search(/\n\s+at\s/);
    if (stackAt > 0 && !msg.querySelector('details')) {
      var frames = text.slice(stackAt).trim().split('\n').length;
      msg.replaceChildren(document.createTextNode(text.slice(0, stackAt)));
      var sd = el('details', 'stack');
      sd.appendChild(el('summary', null, 'Stack trace (' + frames + ' frame' + (frames === 1 ? '' : 's') + ')'));
      var sp = el('pre'); sp.textContent = text.slice(stackAt + 1);
      sd.appendChild(sp);
      msg.appendChild(sd);
    }
    div._text = text;
    div.appendChild(msg);

    if (r.sourceId) {
      var src = document.createElement('span');
      src.className = 'src';
      src.textContent = shortenSource(r.sourceId) + (r.line ? ':' + r.line : '');
      div.appendChild(src);
    }

    // Tap a message for its actions.
    var act = el('div', 'eact');
    act.appendChild(actionButton('Copy', function () { copyText(text); }));
    if (r.sourceId) act.appendChild(actionButton('Copy with source', function () { copyText(text + '\n' + r.sourceId + (r.line ? ':' + r.line : '')); }));
    if (level === 'input') act.appendChild(actionButton('Reuse', function () {
      var inp = document.getElementById('consoleInput');
      inp.value = text;
      inp.focus();
    }));
    div.appendChild(act);
    div.addEventListener('click', function (e) {
      if (e.target.closest('summary, .eact button, details')) return;
      div.classList.toggle('sel');
    });

    applyConsoleFilter(div);
    entries.appendChild(div);
    while (entries.childElementCount > MAX_CONSOLE_ENTRIES) entries.firstElementChild.remove();
    if (level === 'warn') consoleWarnCount++;
    else if (level === 'error' || level === 'result_error') consoleErrorCount++;
    else if (level !== 'input' && level !== 'result') consoleLogCount++;
    updateConsoleTotals();
    if (!consoleScrolledUp) entries.scrollTop = entries.scrollHeight;
  }

  function updateConsoleTotals() {
    document.getElementById('consoleLogTotal').textContent = consoleLogCount;
    document.getElementById('consoleWarnTotal').textContent = consoleWarnCount;
    document.getElementById('consoleErrorTotal').textContent = consoleErrorCount;
  }

  function shortenSource(src) {
    var slash = src.lastIndexOf('/');
    return slash === -1 ? src : src.slice(slash + 1);
  }

  function clearConsole() {
    document.getElementById('consoleEntries').replaceChildren();
    consoleScrolledUp = false;
    consoleLogCount = 0;
    consoleWarnCount = 0;
    consoleErrorCount = 0;
    updateConsoleTotals();
  }

  function submitEval(source) {
    if (!source || !source.trim()) return;
    // Pin to bottom when the user runs a command — they want to see the result, even
    // if they had scrolled up to inspect earlier logs.
    consoleScrolledUp = false;
    if (history[history.length - 1] !== source) {
      history.push(source);
      if (history.length > MAX_HISTORY) history.shift();
      savePref('cmd_history', JSON.stringify(history));
    }
    historyCursor = -1;
    draft = '';
    appendConsoleEntry({ level: 'input', message: source });
    var evalId = 'e-' + (++evalSeq);
    pendingEvals.add(evalId);
    try {
      AndroidPanel.postMessage(JSON.stringify({ type: 'eval', evalId: evalId, source: source }));
    } catch (e) {
      appendConsoleEntry({ level: 'result_error', message: String(e) });
      pendingEvals.delete(evalId);
    }
  }

  function deliverEvalResult(evalId, resultJson) {
    if (!pendingEvals.delete(evalId)) return;
    var parsed;
    try { parsed = JSON.parse(resultJson); } catch (e) { parsed = [false, 'unparseable result: ' + resultJson]; }
    if (!Array.isArray(parsed)) parsed = [false, 'unexpected result shape'];
    appendConsoleEntry({ level: parsed[0] ? 'result' : 'result_error', message: String(parsed[1]) });
  }

  // Level filters: four segments in the bar, Info and Debug in the console menu; all share one state.
  function setConsoleFilter(f) {
    consoleFilter = f;
    document.querySelectorAll('#console-tab .seg[data-f], #console-tab .opt[data-f]').forEach(function (c) {
      c.classList.toggle('on', c.dataset.f === f);
    });
    refilterConsole();
  }
  // Document-level because Info and Debug are inside the console menu sheet, which lives outside the console tab.
  document.addEventListener('click', function (e) {
    var chip = e.target.closest('#console-tab .seg[data-f], .sheet .opt[data-f]');
    if (chip) setConsoleFilter(chip.dataset.f);
  });
  document.getElementById('cSearchBtn').addEventListener('click', function () {
    var row = document.getElementById('cSearchRow');
    row.hidden = !row.hidden;
    this.classList.toggle('on', !row.hidden);
    if (!row.hidden) document.getElementById('consoleSearch').focus();
    else if (consoleQuery) { document.getElementById('consoleSearch').value = ''; consoleQuery = ''; refilterConsole(); }
  });
  // The secondary actions live in #cSheetSrc; the sheet borrows them (handlers intact) and gives them back.
  document.getElementById('cMenuBtn').addEventListener('click', function () {
    var src = document.getElementById('cSheetSrc');
    var moved = Array.prototype.slice.call(src.childNodes);
    var sh = openSheet('Console', function (box) { moved.forEach(function (n) { box.appendChild(n); }); });
    sh.parentNode._restore = function () { moved.forEach(function (n) { src.appendChild(n); }); };
    sh.addEventListener('click', function (e) { if (e.target.closest('.mi')) closeSheet(); });
  });
  document.getElementById('consoleSearch').addEventListener('input', function () {
    consoleQuery = this.value.trim().toLowerCase();
    refilterConsole();
  });

  function consoleRows() {
    var rows = [];
    var kids = document.getElementById('consoleEntries').children;
    for (var i = 0; i < kids.length; i++) {
      var d = kids[i];
      if (!d._level || d.classList.contains('hide')) continue;
      rows.push({ time: d._time, level: d._level, message: d._text || '', source: d._src || '', line: d._line || 0 });
    }
    return rows;
  }
  function consoleTxt(rows) {
    return rows.map(function (r) {
      return '[' + hms(r.time) + '] ' + r.level.toUpperCase() + ' ' + r.message + (r.source ? '  (' + r.source + (r.line ? ':' + r.line : '') + ')' : '');
    }).join('\n');
  }
  document.getElementById('cCopy').addEventListener('click', function () {
    var rows = consoleRows();
    if (rows.length) copyText(consoleTxt(rows));
  });
  document.getElementById('cTxt').addEventListener('click', function () {
    var rows = consoleRows();
    if (rows.length) shareText('Netex console (' + rows.length + ' messages)', consoleTxt(rows));
  });
  document.getElementById('cJson').addEventListener('click', function () {
    var rows = consoleRows();
    if (rows.length) shareText('Netex console.json', JSON.stringify(rows.map(function (r) {
      return { time: new Date(r.time).toISOString(), level: r.level, message: r.message, source: r.source, line: r.line };
    }), null, 2));
  });
  document.getElementById('cClear').addEventListener('click', clearConsole);
  var showTimes = false;
  document.getElementById('cTime').addEventListener('click', function () {
    showTimes = !showTimes;
    this.classList.toggle('on', showTimes);
    document.getElementById('consoleEntries').classList.toggle('ts-on', showTimes);
    savePref('timestamps', showTimes);
  });
  var keepConsole = false;
  document.getElementById('cKeep').addEventListener('click', function () {
    keepConsole = !keepConsole;
    this.classList.toggle('on', keepConsole);
    savePref('keep_console', keepConsole);
  });

  document.getElementById('sourceCopy').addEventListener('click', function () { if (lastSource) copyText(lastSource); });

  function wireConsoleScroll() {
    var entries = document.getElementById('consoleEntries');
    if (!entries) return;
    entries.addEventListener('scroll', function () {
      consoleScrolledUp = entries.scrollTop + entries.clientHeight < entries.scrollHeight - 4;
    });
  }
  wireConsoleScroll();

  var multiline = false;

  function stepHistory(dir) {
    var input = document.getElementById('consoleInput');
    if (history.length === 0) return;
    if (dir < 0) {
      if (historyCursor === -1) draft = input.value;
      historyCursor = historyCursor === -1 ? history.length - 1 : Math.max(0, historyCursor - 1);
      input.value = history[historyCursor];
    } else {
      if (historyCursor === -1) return;
      historyCursor = historyCursor + 1;
      if (historyCursor >= history.length) { historyCursor = -1; input.value = draft; }
      else input.value = history[historyCursor];
    }
    autoGrow(input);
  }

  function autoGrow(input) {
    input.style.height = 'auto';
    input.style.height = Math.min(input.scrollHeight, 140) + 'px';
  }

  function wireConsolePrompt() {
    var form = document.getElementById('consolePrompt');
    var input = document.getElementById('consoleInput');
    if (!form || !input) return;
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var src = input.value;
      input.value = '';
      autoGrow(input);
      submitEval(src);
    });
    input.addEventListener('input', function () { autoGrow(input); });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !multiline && !e.shiftKey) {
        e.preventDefault();
        form.requestSubmit ? form.requestSubmit() : form.dispatchEvent(new Event('submit', { cancelable: true }));
      } else if (e.key === 'ArrowUp' && !multiline) {
        e.preventDefault();
        stepHistory(-1);
      } else if (e.key === 'ArrowDown' && !multiline) {
        e.preventDefault();
        stepHistory(1);
      }
    });
    document.getElementById('cPrev').addEventListener('click', function () { stepHistory(-1); input.focus(); });
    document.getElementById('cNext').addEventListener('click', function () { stepHistory(1); input.focus(); });
    document.getElementById('cMulti').addEventListener('click', function () {
      multiline = !multiline;
      this.style.background = multiline ? 'var(--pc)' : '';
      this.style.color = multiline ? 'var(--opc)' : '';
      form.classList.toggle('multi', multiline);
      input.rows = multiline ? 4 : 1;
      input.placeholder = multiline ? 'Multi-line JavaScript — tap Run to execute' : 'Run JavaScript';
      autoGrow(input);
      input.focus();
    });
    document.getElementById('cHistClear').addEventListener('click', function () {
      history = [];
      historyCursor = -1;
      savePref('cmd_history', '[]');
      appendConsoleEntry({ level: 'info', message: 'Command history cleared' });
    });
  }
  wireConsolePrompt();

  function setPrefs(o) {
    if (!o) return;
    showTimes = !!o.timestamps;
    document.getElementById('cTime').classList.toggle('on', showTimes);
    document.getElementById('consoleEntries').classList.toggle('ts-on', showTimes);
    keepConsole = !!o.keepConsole;
    document.getElementById('cKeep').classList.toggle('on', keepConsole);
    keepNetwork = !!o.keepNetwork;
    document.getElementById('nKeep').classList.toggle('on', keepNetwork);
    if (o.maxRows) maxRows = o.maxRows;
    if (Array.isArray(o.history)) {
      history = o.history.filter(function (x) { return typeof x === 'string'; }).slice(-MAX_HISTORY);
      historyCursor = -1;
    }
  }

  // Preserve-log mode: old rows stay, a divider marks the navigation.
  function markNavigation(kind, url, navId) {
    if (kind === 'console') {
      var entries = document.getElementById('consoleEntries');
      entries.appendChild(el('div', 'sep', 'Navigated to ' + url));
      if (!consoleScrolledUp) entries.scrollTop = entries.scrollHeight;
    } else {
      var rows = netList.querySelectorAll('div.req');
      if (!rows.length) return;
      // Request ids are unique per request, so old rows keep theirs: a late completion still updates its own row.
      rows.forEach(function (d) { d.dataset.stale = '1'; d.classList.add('old'); });
      netList.appendChild(el('div', 'navsep', 'Navigation' + (navId ? ' #' + navId : '') + ' to ' + url));
    }
  }

  // ======================================================================================
  // Developer tools: protection status, navigation history, events, detection details
  // ======================================================================================
  var maxRows = 2000;

  function devtools(action, extra) {
    var m = { type: 'devtools', action: action };
    if (extra) for (var k in extra) m[k] = extra[k];
    try { AndroidPanel.postMessage(JSON.stringify(m)); } catch (e) {}
  }

  function trimRows() {
    var rows = netList.querySelectorAll('div.req');
    var extra = rows.length - maxRows;
    for (var i = 0; i < extra; i++) {
      var d = rows[i];
      totalBytes -= parseInt(d.dataset.lastSize, 10) || 0;
      totalCount--;
      d.remove();
    }
    if (extra > 0) updateTotals();
  }

  function patchNetworkRow(id, status, failed, error) {
    var det = netList.querySelector('div.req[data-rid="' + id + '"]');
    if (!det || !det._r) return;
    var r = det._r;
    if (status > 0) r.status = status;
    if (failed) r.failed = true;
    if (error) r.error = error;
    r.pending = false;
    renderRow(det, r);
  }

  // Navigation started with Preserve off: keep only the kinds the user chose to keep.
  function clearForNavigation(keepFailed, keepDocs) {
    netList.querySelectorAll('div.req').forEach(function (d) {
      if ((keepFailed && d.dataset.failed === '1') || (keepDocs && d.dataset.isnav === '1')) return;
      totalBytes -= parseInt(d.dataset.lastSize, 10) || 0;
      totalCount--;
      d.remove();
    });
    netList.querySelectorAll('.navsep').forEach(function (n) { n.remove(); });
    if (!netList.querySelector('div.req')) showEmptyNetwork();
    updateTotals();
  }

  var devState = null;
  function setDevStatus(o) {
    devState = o;
    var box = document.getElementById('devStatus');
    box.replaceChildren();
    function pill(text, cls, onClick) {
      var b = el('button', 'pill ' + (cls || ''), text);
      if (onClick) b.addEventListener('click', onClick);
      box.appendChild(b);
    }
    pill(o.enabled ? (o.auto ? 'Auto protection on' : 'Auto protection off') : 'DevTools compat off', o.enabled && o.auto ? 'ok' : '', function () { devtools('open-settings'); });
    if (o.compat) pill('Compat mode', 'warn', function () { devtools('get-detection'); });
    if (o.activated) pill(o.activated + ' protection' + (o.activated > 1 ? 's' : '') + ' active', 'warn', function () { devtools('get-detection'); });
    if (o.loop && o.loop !== 'none') pill('Loop: ' + o.loop, 'err', function () { devtools('get-nav'); });
    if (o.recording) pill(o.recordingPersist ? 'Recording persists' : 'Recording pauses on navigation', o.recordingPersist ? 'ok' : 'warn');
    if (o.detection && o.detection !== 'none') pill('Interference: ' + o.detection, 'err', function () { devtools('get-detection'); });
    box.style.display = box.childNodes.length ? '' : 'none';
  }

  function hostPath(u) {
    try { var x = new URL(u); return x.host + (x.pathname === '/' ? '/' : x.pathname) + x.search; } catch (e) { return u; }
  }

  function showNavHistory(json) {
    var data; try { data = JSON.parse(json); } catch (e) { return; }
    var ov = overlay('Navigation history');
    var b = ov.body;
    if (!data.tracking) b.appendChild(el('div', 'note', 'Native navigation tracking is off (Settings, Developer Tools, Navigation Protection).'));
    var navs = data.navs || [];
    if (!navs.length) { b.appendChild(el('div', 'empty', 'No navigations recorded yet.')); return; }
    if (data.loop && data.loop.level !== 'none') {
      var seq = (data.loop.sequence || []).map(function (s) { return '#' + s.id + ' ' + hostPath(s.url); });
      var warn = el('div', 'note warnbox');
      warn.appendChild(el('b', null, 'Loop detected (' + data.loop.level + ')'));
      warn.appendChild(el('div', null, seq.join('  →  ')));
      var row = el('div', 'actions');
      var stop = el('button', 'chip', data.holding ? 'Continue navigation' : 'Stop automatic navigation');
      stop.addEventListener('click', function () { devtools(data.holding ? 'continue-nav' : 'stop-nav'); ov.el.remove(); });
      row.appendChild(stop);
      warn.appendChild(row);
      b.appendChild(warn);
    }
    b.appendChild(el('div', 'note', 'Native counts every request WebView reports. The page-captured list can be shorter: requests the page cannot see are not available.'));
    var rows = netList.querySelectorAll('div.req');
    navs.forEach(function (n) {
      var card = el('div', 'navcard');
      var head = el('div', 'navhead');
      head.appendChild(el('b', null, 'Navigation #' + n.id));
      var tags = [n.type + (n.inferred ? ' (inferred)' : ''), n.status, n.user ? 'user' : 'automatic'];
      if (n.repeat > 1) tags.push('URL seen ' + n.repeat + 'x');
      if (n.restored) tags.push('from last run');
      head.appendChild(el('span', 'dim', ' ' + tags.join(' · ')));
      card.appendChild(head);
      card.appendChild(el('div', 'navurl', n.url));
      if (n.hops.length > 1) {
        var chain = el('div', 'chain');
        chain.appendChild(el('div', 'dim', 'Redirect chain (' + n.hops.length + ' requests)'));
        n.hops.forEach(function (h, i) { chain.appendChild(el('div', null, (i + 1) + '. ' + (h.redirect ? '↪ ' : '') + h.url)); });
        card.appendChild(chain);
      }
      var mine = [];
      rows.forEach(function (d) { if (d.dataset.nav === String(n.id) && d._r) mine.push(d._r); });
      var info = 'Captured ' + mine.length + ' request' + (mine.length === 1 ? '' : 's') + ' in the log; WebView reported ' + n.native +
        (n.frames ? ' (' + n.frames + ' look like frame documents)' : '') + '.';
      if (n.error) info += ' Error: ' + n.error + '.';
      if (n.http) info += ' HTTP ' + n.http + '.';
      card.appendChild(el('div', 'dim', info));
      mine.slice(0, 40).forEach(function (r) { card.appendChild(el('div', 'navreq', r.method + ' ' + hostPath(r.url))); });
      if (mine.length > 40) card.appendChild(el('div', 'dim', '…and ' + (mine.length - 40) + ' more'));
      b.appendChild(card);
    });
    var foot = el('div', 'actions');
    var clr = el('button', 'chip', 'Clear history');
    clr.addEventListener('click', function () { devtools('clear-nav'); ov.el.remove(); });
    foot.appendChild(clr);
    b.appendChild(foot);
  }

  var evFilter = 'all', evQuery = '', evData = [];
  var evOverlay = null;
  function showEvents(json) {
    try { evData = JSON.parse(json); } catch (e) { return; }
    if (!evOverlay || !evOverlay.el.isConnected) {
      evOverlay = overlay('Events');
      var bar = el('div', 'bar');
      ['all', 'info', 'warning', 'critical'].forEach(function (f) {
        var c = el('button', 'chip' + (f === evFilter ? ' on' : ''), f === 'all' ? 'All' : f.charAt(0).toUpperCase() + f.slice(1));
        c.addEventListener('click', function () {
          evFilter = f;
          bar.querySelectorAll('.chip[data-f]').forEach(function (x) { x.classList.toggle('on', x === c); });
          drawEvents();
        });
        c.dataset.f = f;
        bar.appendChild(c);
      });
      var q = el('input'); q.type = 'search'; q.placeholder = 'Search events'; q.value = evQuery;
      q.addEventListener('input', function () { evQuery = q.value.trim().toLowerCase(); drawEvents(); });
      bar.appendChild(q);
      var exp = el('button', 'chip', 'Export');
      exp.addEventListener('click', function () { devtools('export-events'); });
      var clr = el('button', 'chip', 'Clear');
      clr.addEventListener('click', function () { devtools('clear-events'); });
      bar.appendChild(exp); bar.appendChild(clr);
      evOverlay.body.appendChild(bar);
      evOverlay.list = el('div', 'evlist');
      evOverlay.body.appendChild(evOverlay.list);
    }
    drawEvents();
  }

  function drawEvents() {
    var list = evOverlay.list;
    list.replaceChildren();
    var shown = evData.filter(function (e) {
      if (evFilter !== 'all' && e.severity !== evFilter) return false;
      if (!evQuery) return true;
      return (e.label + ' ' + e.url + ' ' + e.reason + ' ' + e.actions.join(' ')).toLowerCase().indexOf(evQuery) !== -1;
    });
    if (!shown.length) { list.appendChild(el('div', 'empty', 'No events.')); return; }
    shown.forEach(function (e) {
      var card = el('div', 'evcard sev-' + e.severity);
      var h = el('div', 'navhead');
      h.appendChild(el('b', null, e.label + (e.count > 1 ? '  ×' + e.count : '')));
      h.appendChild(el('span', 'dim', '  ' + hms(e.time) + ' · ' + e.severity));
      card.appendChild(h);
      card.appendChild(el('div', 'navurl', e.url));
      card.appendChild(el('div', null, e.reason));
      if (e.actions.length) card.appendChild(el('div', 'dim', 'Actions: ' + e.actions.join('; ')));
      list.appendChild(card);
    });
  }

  function showDetection(json) {
    var d; try { d = JSON.parse(json); } catch (e) { return; }
    var ov = overlay('Website interference');
    var b = ov.body;
    if (!d.found) {
      b.appendChild(el('div', 'empty', 'No interference indicators for ' + (d.host || 'this site') + '.'));
    } else {
      var card = el('div', 'navcard');
      card.appendChild(el('b', null, 'Website Interference Detected'));
      [['Type', d.type], ['Confidence', d.confidence], ['Behavior', d.behavior], ['Occurrences', d.occurrences], ['Affected URL', d.host]].forEach(function (p) {
        card.appendChild(el('div', null, p[0] + ': ' + p[1]));
      });
      card.appendChild(el('div', 'dim', 'Indicators are circumstantial. A single debugger statement or size check is common in legitimate code; confidence rises only when several different indicators appear. Compatibility mode reduces interference but cannot defeat every detection method in WebView.'));
      b.appendChild(card);
      var acts = el('div', 'actions');
      [['Preserve Network Logs', 'site-preserve'], ['Enable Navigation Protection', 'site-nav'], ['Enable Compatibility Mode', 'site-compat']].forEach(function (a) {
        var c = el('button', 'chip', a[0]);
        c.addEventListener('click', function () { devtools(a[1]); c.textContent = a[0] + ' ✓'; c.disabled = true; });
        acts.appendChild(c);
      });
      b.appendChild(acts);
      var det = el('div', 'navcard');
      det.appendChild(el('b', null, 'Detection details'));
      (d.indicators || []).forEach(function (i) {
        det.appendChild(el('div', null, i.label + ' ×' + i.count + (i.behavioral ? ' (behaviour)' : '')));
        if (i.detail) det.appendChild(el('div', 'dim', i.detail));
      });
      b.appendChild(det);
    }
    if ((d.activations || []).length) {
      var act = el('div', 'navcard');
      act.appendChild(el('b', null, 'Protections switched on for this session'));
      d.activations.forEach(function (a) { act.appendChild(el('div', null, a.label + ' — ' + a.reason)); });
      b.appendChild(act);
    }
  }


  var HAR_LABELS = { all: 'All', doc: 'Doc', js: 'JS', css: 'CSS', img: 'Img', media: 'Media', font: 'Font', fetch: 'Fetch', xhr: 'XHR', data: 'Data', other: 'Other', failed: 'Failed', nav: 'Nav', err: 'Errors', slow: 'Slow' };
  function showHar(json) {
    var d; try { d = JSON.parse(json); } catch (e) { return; }
    var ov = overlay('HAR: ' + (HAR_LABELS[d.filter] || d.filter) + ' (' + d.count + ' entr' + (d.count === 1 ? 'y' : 'ies') + ')');
    var b = ov.body;
    var kb = d.bytes > 1048576 ? (d.bytes / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(d.bytes / 1024)) + ' KB';
    b.appendChild(el('div', 'note', d.name + ' · ' + kb + ' · ' + (d.source === 'recording'
      ? 'from the HAR recording'
      : 'built from the Network tab (no recording was running, so bodies are trimmed)')));
    var acts = el('div', 'actions');
    [['Share', 'share'], ['Save', 'save'], ['HAR settings', 'settings']].forEach(function (a) {
      var c = el('button', 'chip', a[0]);
      c.addEventListener('click', function () { try { AndroidPanel.postMessage(JSON.stringify({ type: 'har-open', action: a[1] })); } catch (e) {} });
      acts.appendChild(c);
    });
    b.appendChild(acts);
    if (!d.count) { b.appendChild(el('div', 'empty', 'No ' + (HAR_LABELS[d.filter] || d.filter) + ' requests to show yet.')); return; }
    if (d.truncated) b.appendChild(el('div', 'note', 'Showing the first 600 KB. Share or Save gives the complete file.'));
    var pre = el('pre', 'harpre');
    pre.textContent = d.preview;
    b.appendChild(pre);
  }


  // ======================================================================================
  // Network: selection, row menu, request inspector
  // ======================================================================================
  var inspected = null;
  var inspTab = 'overview';
  var respRaw = false, respPretty = true, respQuery = '';
  var selecting = false;
  var selected = new Set();
  var netTab = document.getElementById('network-tab');

  function isWide() { return netTab.classList.contains('wide'); }

  // Rows: tap opens the inspector, long press opens the action sheet.
  (function () {
    var timer = null, sx = 0, sy = 0, longHit = false;
    netList.addEventListener('pointerdown', function (e) {
      var d = e.target.closest('.req');
      if (!d) return;
      longHit = false; sx = e.clientX; sy = e.clientY;
      clearTimeout(timer);
      timer = setTimeout(function () { timer = null; longHit = true; openRowMenu(d); }, 450);
    });
    netList.addEventListener('pointermove', function (e) {
      if (timer && (Math.abs(e.clientX - sx) > 8 || Math.abs(e.clientY - sy) > 8)) { clearTimeout(timer); timer = null; }
    });
    ['pointerup', 'pointercancel', 'pointerleave'].forEach(function (ev) {
      netList.addEventListener(ev, function () { if (timer) { clearTimeout(timer); timer = null; } });
    });
    netList.addEventListener('click', function (e) {
      var d = e.target.closest('.req');
      if (!d) return;
      if (longHit) { longHit = false; return; }
      if (selecting) toggleSelect(d); else openInspector(d);
    });
    netList.addEventListener('contextmenu', function (e) { if (e.target.closest('.req')) e.preventDefault(); });
  })();

  function enterSelection(first) {
    selecting = true;
    netList.classList.add('selecting');
    document.getElementById('netBar').style.display = 'none';
    document.getElementById('selBar').classList.add('on');
    if (first) toggleSelect(first);
    updateSelCount();
  }
  function exitSelection() {
    selecting = false;
    selected.forEach(function (d) { d.classList.remove('sel'); var c = d.querySelector('.chk'); if (c) c.textContent = ''; });
    selected.clear();
    netList.classList.remove('selecting');
    document.getElementById('netBar').style.display = '';
    document.getElementById('selBar').classList.remove('on');
  }
  function toggleSelect(d) {
    var on = !selected.has(d);
    if (on) selected.add(d); else selected.delete(d);
    d.classList.toggle('sel', on);
    var c = d.querySelector('.chk');
    if (c) c.textContent = on ? '✓' : '';
    updateSelCount();
  }
  function updateSelCount() {
    document.getElementById('selCount').textContent = selected.size + ' selected';
  }
  function selectedRecords() {
    var out = [];
    selected.forEach(function (d) { if (d._r) out.push(d._r); });
    return out;
  }
  document.getElementById('selCancel').addEventListener('click', exitSelection);
  document.getElementById('selAll').addEventListener('click', function () {
    netList.querySelectorAll('.req:not(.hide)').forEach(function (d) { if (!selected.has(d)) toggleSelect(d); });
  });
  document.getElementById('selCopy').addEventListener('click', function () {
    var urls = selectedRecords().map(function (r) { return r.url; });
    if (urls.length) copyText(urls.join('\n'));
  });
  document.getElementById('selExport').addEventListener('click', function () {
    var rows = selectedRecords();
    if (rows.length) shareText('Netex requests (' + rows.length + ')', JSON.stringify(rows.map(recordJson), null, 2));
  });

  function blockUrlOf(r) {
    try { var x = new URL(r.url); return x.origin + x.pathname; } catch (e) { return r.url; }
  }

  function openRowMenu(det) {
    var r = det._r;
    if (!r) return;
    openSheet(r.method + ' ' + hostOf(r.url), function (sh) {
      sh.appendChild(menuItem('eye', 'Open details', null, function () { openInspector(det); }));
      sh.appendChild(menuItem('all', 'Select', 'Pick several requests to copy or export', function () { enterSelection(det); }));
      sh.appendChild(menuItem('copy', 'Copy URL', null, function () { copyText(r.url); }));
      sh.appendChild(menuItem('copy', 'Copy as cURL', null, function () { copyText(toCurl(r)); }));
      if (!r.pending) sh.appendChild(menuItem('play', 'Replay request…', null, function () { openReplay(r); }));
      sh.appendChild(menuItem('share', 'Share', null, function () { shareText(r.method + ' ' + hostOf(r.url), requestText(r)); }));
      sh.appendChild(menuItem('block', 'Block this URL', null, function () { postBlock(blockUrlOf(r)); }, 'danger'));
      sh.appendChild(menuItem('block', 'Block domain', hostOf(r.url), function () { postBlock(hostOf(r.url)); }, 'danger'));
    });
  }

  function openInspector(det) {
    if (inspected && inspected !== det) {
      inspected.classList.remove('cur');
      respRaw = false; respQuery = '';
    }
    inspected = det;
    det.classList.add('cur');
    document.getElementById('inspector').classList.add('open');
    renderInspector();
  }

  function closeInspector() {
    if (inspected) inspected.classList.remove('cur');
    inspected = null;
    var ins = document.getElementById('inspector');
    ins.classList.remove('open');
    ins.replaceChildren();
    if (isWide()) renderInspector();
  }

  function kvRow(k, v, opts) {
    opts = opts || {};
    var row = el('div', 'kvr');
    row.appendChild(el('span', 'k', k));
    var val = el('span', 'v' + (v == null || v === '' ? ' na' : ''), v == null || v === '' ? (opts.na || 'Not available') : String(v));
    row.appendChild(val);
    if (opts.copy && v) {
      var cp = el('button', 'cp');
      cp.setAttribute('aria-label', 'Copy ' + k);
      cp.innerHTML = svgIcon('copy');
      cp.addEventListener('click', function (e) { e.stopPropagation(); copyText(opts.copy === true ? String(v) : opts.copy); });
      row.appendChild(cp);
    }
    return row;
  }

  function card(title, actions) {
    var c = el('div', 'card');
    if (title) {
      var h = el('h5', null, title);
      h.appendChild(el('span', 'sp'));
      (actions || []).forEach(function (a) {
        var b = el('button', null, a[0]);
        b.addEventListener('click', a[1]);
        h.appendChild(b);
      });
      c.appendChild(h);
    }
    return c;
  }

  function statusFull(r) {
    if (r.pending) return 'Pending';
    if (isBlocked(r)) return 'Blocked by a Netex request rule';
    if (r.failed) return 'Failed' + (r.error ? ': ' + r.error : '');
    if (r.status === 0) return r.capture === 'native' ? 'Status not exposed by WebView' : 'No response';
    return r.status + ' ' + statusLabel(r)[1];
  }

  function initiatorText(r) {
    if (r.capture === 'native') return 'Reported by WebView (page script did not report it)';
    if (r.kind === 'fetch') return 'fetch() call from the page';
    if (r.kind === 'xhr') return 'XMLHttpRequest from the page';
    if (r.kind === 'document') return 'Page navigation';
    if (r.kind) return 'Resource requested by the page (' + r.kind + ')';
    return null;
  }

  function headerValue(list, name) {
    var v = null;
    (list || []).forEach(function (h) { if (h[0] && h[0].toLowerCase() === name) v = h[1]; });
    return v;
  }

  function renderInspector() {
    var ins = document.getElementById('inspector');
    var oldBody = ins.querySelector('.ibody');
    var top = oldBody ? oldBody.scrollTop : 0;
    ins.replaceChildren();
    if (!inspected || !inspected._r) {
      ins.appendChild(el('div', 'placeholder', 'Select a request to inspect its headers, payload, response and timing.'));
      return;
    }
    var r = inspected._r;
    var head = el('div', 'head');
    var back = iconButton('back', 'Back to requests', closeInspector);
    back.classList.add('backb');
    head.appendChild(back);
    head.appendChild(el('span', 't', 'Request Details'));
    head.appendChild(iconButton('more', 'Request actions', function () { openInspectorMenu(r); }));
    ins.appendChild(head);

    var parts = splitUrl(r.url || '');
    var hero = el('div', 'hero');
    hero.appendChild(el('div', 'line1', r.method + ' ' + midTruncate(parts.path, 110)));
    var l2 = el('div', 'line2');
    var st = stateOf(r), lab = statusLabel(r);
    var sb = el('b', null, r.pending ? 'Pending' : (r.status ? r.status + ' ' + lab[1] : lab[0] + ' ' + lab[1]));
    sb.style.color = { 's-ok': 'var(--ok)', 's-redir': 'var(--primary)', 's-c4': 'var(--warn)', 's-c5': 'var(--err)', 's-fail': 'var(--err)' }[st] || 'var(--fg-dim)';
    l2.appendChild(sb);
    var extra = [parts.host];
    if (r.size) extra.push(formatSize(r.size));
    if (r.durationMs) extra.push(r.durationMs + ' ms');
    l2.appendChild(document.createTextNode('  ·  ' + extra.join('  ·  ')));
    hero.appendChild(l2);
    ins.appendChild(hero);

    var tabs = el('div', 'itabs');
    [['overview', 'Overview'], ['headers', 'Headers'], ['payload', 'Payload'], ['response', 'Response'], ['timing', 'Timing']].forEach(function (t) {
      var b = el('button', 'itab' + (inspTab === t[0] ? ' on' : ''), t[1]);
      b.addEventListener('click', function () { inspTab = t[0]; renderInspector(); });
      tabs.appendChild(b);
    });
    ins.appendChild(tabs);

    var body = el('div', 'ibody');
    ({ overview: tabOverview, headers: tabHeaders, payload: tabPayload, response: tabResponse, timing: tabTiming })[inspTab](body, r);
    ins.appendChild(body);
    body.scrollTop = top;
  }

  function tabOverview(b, r) {
    var c = card();
    c.appendChild(kvRow('URL', r.url, { copy: true }));
    c.appendChild(kvRow('Method', r.method));
    c.appendChild(kvRow('Status', statusFull(r)));
    c.appendChild(kvRow('Resource type', typeLabel(typeOf(r), r) + (r.contentType ? ' · ' + r.contentType.split(';')[0] : '')));
    c.appendChild(kvRow('Initiator', initiatorText(r)));
    c.appendChild(kvRow('Duration', r.durationMs ? r.durationMs + ' ms' : null, { na: 'Not reported' }));
    c.appendChild(kvRow('Transfer size', r.size ? formatSize(r.size) : null, { na: 'Not reported' }));
    c.appendChild(kvRow('Navigation ID', r.navId ? '#' + r.navId : null, { na: 'Not tracked' }));
    var loc = headerValue(r.resHeaders, 'location');
    c.appendChild(kvRow('Redirect',
      r.redirectHop ? 'Redirect hop (WebView does not expose its status or target)'
        : (r.status >= 300 && r.status < 400) ? 'Redirects to ' + (loc || '(Location header not exposed)')
        : 'Not a redirect'));
    c.appendChild(kvRow('Capture', r.capture === 'native' ? 'Partial: WebView reports URL, method and request headers only' : 'Full: reported by the page'));
    if (r.error) c.appendChild(kvRow('Error', r.error));
    b.appendChild(c);
  }

  function headerCard(title, list, emptyText) {
    var c = card(title + (list && list.length ? ' (' + list.length + ')' : ''),
      list && list.length ? [['Copy all', function () { copyText(headersText(list)); }]] : []);
    if (!list || !list.length) { c.appendChild(el('div', 'kvr')).appendChild(el('span', 'v na', emptyText)); return c; }
    list.forEach(function (h) {
      var row = el('div', 'kvr');
      row.appendChild(el('span', 'k', h[0]));
      row.appendChild(el('span', 'v', h[1]));
      var cp = el('button', 'cp');
      cp.setAttribute('aria-label', 'Copy header ' + h[0]);
      cp.innerHTML = svgIcon('copy');
      cp.addEventListener('click', function () { copyText(h[0] + ': ' + h[1]); });
      row.appendChild(cp);
      c.appendChild(row);
    });
    return c;
  }

  function tabHeaders(b, r) {
    var g = card('General');
    g.appendChild(kvRow('Request URL', r.url, { copy: true }));
    g.appendChild(kvRow('Request method', r.method));
    g.appendChild(kvRow('Status code', r.pending ? 'Pending' : (r.status ? String(r.status) : null), { na: r.capture === 'native' ? 'Not exposed by WebView' : 'No response' }));
    b.appendChild(g);
    b.appendChild(headerCard('Request headers', r.reqHeaders, 'No request headers were captured'));
    b.appendChild(headerCard('Response headers', r.resHeaders, r.pending ? 'Response pending' : 'No response headers were captured'));
  }

  function tabPayload(b, r) {
    var qp = queryPairs(r.url);
    if (qp.length) {
      var q = card('Query parameters (' + qp.length + ')', [['Copy', function () { copyText(qp.map(function (p) { return p[0] + '=' + p[1]; }).join('\n')); }]]);
      qp.forEach(function (p) { q.appendChild(kvRow(p[0], p[1] === '' ? '(empty)' : p[1])); });
      b.appendChild(q);
    }
    var raw = r.reqBody || '';
    var ct = headerValue(r.reqHeaders, 'content-type') || '';
    var kind = !raw ? 'none' : tryJson(raw).ok ? 'JSON' : /x-www-form-urlencoded/i.test(ct) ? 'URL-encoded form' : /multipart\/form-data/i.test(ct) ? 'Multipart form data' : 'Raw';
    var c = card('Request body' + (raw ? ' · ' + kind : ''), raw ? [['Copy', function () { copyText(raw); }]] : []);
    if (!raw) {
      c.appendChild(el('div', 'kvr')).appendChild(el('span', 'v na', qp.length ? 'No request body' : 'No request body or query parameters'));
      b.appendChild(c);
      return;
    }
    b.appendChild(c);
    var box = el('div', 'codebox');
    if (kind === 'JSON') {
      var jv = jsonViewer(tryJson(raw).value);
      box.appendChild(jv);
    } else if (kind === 'URL-encoded form') {
      var fc = el('div', 'kv');
      try { new URLSearchParams(raw).forEach(function (v, k) { fc.appendChild(kvRow(k, v === '' ? '(empty)' : v)); }); } catch (e) { fc.appendChild(el('pre', null, raw)); }
      box.appendChild(fc);
    } else {
      var pre = el('pre'); pre.textContent = raw; box.appendChild(pre);
    }
    b.appendChild(box);
  }

  function beautify(r, text) {
    var ct = (r.contentType || '').toLowerCase();
    try {
      if (ct.indexOf('json') !== -1) return prettyJson(text);
      if (ct.indexOf('html') !== -1 && window.html_beautify) return window.html_beautify(text, { indent_size: 2 });
      if (ct.indexOf('xml') !== -1 && window.html_beautify) return window.html_beautify(text, { indent_size: 2 });
      if (ct.indexOf('css') !== -1 && window.css_beautify) return window.css_beautify(text, { indent_size: 2 });
      if ((ct.indexOf('javascript') !== -1 || ct.indexOf('ecmascript') !== -1) && window.js_beautify && text.length < 400000) return window.js_beautify(text, { indent_size: 2 });
    } catch (e) { /* fall through to the original text */ }
    return text;
  }

  function fileNameFor(r) {
    var base = 'response';
    try { var seg = new URL(r.url).pathname.split('/').filter(Boolean).pop(); if (seg) base = seg; } catch (e) {}
    if (!/\.[a-z0-9]{1,5}$/i.test(base)) {
      var ct = (r.contentType || '').toLowerCase();
      base += ct.indexOf('json') !== -1 ? '.json' : ct.indexOf('html') !== -1 ? '.html' : ct.indexOf('xml') !== -1 ? '.xml' : ct.indexOf('css') !== -1 ? '.css' : ct.indexOf('javascript') !== -1 ? '.js' : '.txt';
    }
    return base.replace(/[^\w.\-]+/g, '_');
  }

  function markMatches(pre, q) {
    var text = pre.textContent;
    if (!q) return 0;
    var lower = text.toLowerCase(), needle = q.toLowerCase(), idx = 0, count = 0, out = document.createDocumentFragment(), last = 0;
    while ((idx = lower.indexOf(needle, last)) !== -1 && count < 500) {
      out.appendChild(document.createTextNode(text.slice(last, idx)));
      var m = document.createElement('mark');
      m.textContent = text.slice(idx, idx + needle.length);
      out.appendChild(m);
      last = idx + needle.length;
      count++;
    }
    out.appendChild(document.createTextNode(text.slice(last)));
    pre.replaceChildren(out);
    return count;
  }

  function tabResponse(b, r) {
    if (r.pending) { b.appendChild(card()).appendChild(el('div', 'kvr')).appendChild(el('span', 'v na', 'Response pending')); return; }
    var media = isImage(r) || isModel(r);
    var raw = r.body || '';
    var ct = (r.contentType || '').toLowerCase();
    var isJson = ct.indexOf('json') !== -1 || (raw && tryJson(raw).ok);

    if (media) {
      var box = el('div', 'codebox');
      var node = buildMedia(r);
      box.appendChild(node);
      b.appendChild(box);
      if (r.requestId) requestBlob(r.requestId, function (dataUrl) { if (dataUrl) node.src = dataUrl; });
      var mc = card('Media');
      mc.appendChild(kvRow('Content type', r.contentType));
      mc.appendChild(kvRow('Size', r.size ? formatSize(r.size) : null));
      b.appendChild(mc);
      return;
    }
    if (!raw) {
      var meta = card('Response body');
      meta.appendChild(kvRow('Body', r.size ? 'Not captured as text (binary or too large)' : (r.status === 204 ? 'No content' : 'Empty response')));
      meta.appendChild(kvRow('Content type', r.contentType));
      meta.appendChild(kvRow('Size', r.size ? formatSize(r.size) : null, { na: 'Not reported' }));
      b.appendChild(meta);
      return;
    }

    var tools = el('div', 'tools');
    var canPreview = isJson || ct.indexOf('html') !== -1;
    if (canPreview) {
      var pv = el('button', 'opt' + (!respRaw ? ' on' : ''), 'Preview');
      var rw = el('button', 'opt' + (respRaw ? ' on' : ''), 'Raw');
      pv.addEventListener('click', function () { respRaw = false; renderInspector(); });
      rw.addEventListener('click', function () { respRaw = true; renderInspector(); });
      tools.appendChild(pv); tools.appendChild(rw);
    }
    var pretty = el('button', 'opt' + (respPretty ? ' on' : ''), 'Pretty');
    pretty.addEventListener('click', function () { respPretty = !respPretty; renderInspector(); });
    tools.appendChild(pretty);
    b.appendChild(tools);

    var tools2 = el('div', 'tools');
    var q = el('input'); q.type = 'search'; q.placeholder = 'Search response'; q.value = respQuery;
    var info = el('span', 'na');
    tools2.appendChild(q);
    tools2.appendChild(info);
    var copy = el('button', 'opt', 'Copy');
    copy.addEventListener('click', function () { copyText(raw); });
    var save = el('button', 'opt', 'Save');
    save.addEventListener('click', function () { devtools('save-text', { name: fileNameFor(r), text: raw }); });
    tools2.appendChild(copy); tools2.appendChild(save);
    b.appendChild(tools2);

    var host = el('div', 'codebox');
    b.appendChild(host);
    function draw() {
      host.replaceChildren();
      info.textContent = '';
      var wantPreview = canPreview && !respRaw && !respQuery;
      if (wantPreview && isJson && tryJson(raw).ok) { host.appendChild(jsonViewer(tryJson(raw).value)); return; }
      if (wantPreview && ct.indexOf('html') !== -1) {
        var frame = document.createElement('iframe');
        frame.className = 'hp';
        frame.setAttribute('sandbox', ''); // no scripts, forms or navigation
        frame.srcdoc = raw;
        host.appendChild(frame);
        return;
      }
      var text = respPretty ? beautify(r, raw) : raw;
      var pre = el('pre');
      var code = document.createElement('code');
      code.textContent = text;
      pre.appendChild(code);
      host.appendChild(pre);
      if (respQuery) {
        var n = markMatches(code, respQuery);
        info.textContent = n + (n >= 500 ? '+' : '') + ' match' + (n === 1 ? '' : 'es');
      } else {
        code.className = ct.indexOf('json') !== -1 ? 'language-json' : ct.indexOf('html') !== -1 ? 'language-html' : ct.indexOf('xml') !== -1 ? 'language-xml' : ct.indexOf('css') !== -1 ? 'language-css' : ct.indexOf('javascript') !== -1 ? 'language-javascript' : 'language-plaintext';
        try { hljs.highlightElement(code); } catch (e) {}
      }
    }
    q.addEventListener('input', function () { respQuery = q.value.trim(); draw(); });
    draw();
    if (r.size && raw.length < r.size) b.appendChild(el('div', 'note', 'Showing the captured part of the body (' + formatSize(raw.length) + ' of ' + formatSize(r.size) + ').')).style.margin = '8px 14px';
  }

  function tabTiming(b, r) {
    var c = card('Timing');
    var t = r.timings;
    var have = t && t.length >= 7;
    function ph(i) { return have && t[i] >= 0 ? t[i] + ' ms' : null; }
    c.appendChild(kvRow('Request start', r.startedAt ? hms(r.startedAt) : null, { na: 'Not reported' }));
    c.appendChild(kvRow('Queued / blocked', ph(0), { na: 'Not reported' }));
    c.appendChild(kvRow('DNS lookup', ph(1), { na: 'Not reported' }));
    c.appendChild(kvRow('Connection', ph(2), { na: 'Not reported' }));
    c.appendChild(kvRow('TLS handshake', ph(3), { na: 'Not reported' }));
    c.appendChild(kvRow('Request sent', ph(4), { na: 'Not reported' }));
    c.appendChild(kvRow('Waiting (first byte)', ph(5), { na: 'Not reported' }));
    c.appendChild(kvRow('Content download', ph(6), { na: 'Not reported' }));
    var resp = have && t[5] >= 0 && t[6] >= 0 ? (t[5] + t[6]) + ' ms' : null;
    c.appendChild(kvRow('Response duration', resp, { na: 'Not reported' }));
    c.appendChild(kvRow('Request duration', have ? sumPhases(t, [0, 1, 2, 3, 4]) : null, { na: 'Not reported' }));
    c.appendChild(kvRow('Total time', r.durationMs ? r.durationMs + ' ms' : null, { na: 'Not reported' }));
    b.appendChild(c);
    if (have) {
      var segs = [];
      ['Queued', 'DNS', 'Connect', 'TLS', 'Sent', 'Wait', 'Download'].forEach(function (n, i) { if (t[i] >= 0) segs.push([n, t[i], i]); });
      var total = segs.reduce(function (a, s) { return a + s[1]; }, 0) || 1;
      var wc = card('Breakdown');
      var bar = el('div', 'tbar');
      segs.forEach(function (s) { var d = el('span', 'tseg t' + s[2]); d.style.width = Math.max(1, s[1] / total * 100) + '%'; bar.appendChild(d); });
      wc.appendChild(bar);
      b.appendChild(wc);
    }
    var note = el('div', 'note', 'Values come from the page\u2019s Performance API. Phases the browser does not report (for example cross-origin requests without Timing-Allow-Origin) are shown as not reported, never estimated.');
    note.style.margin = '10px 14px';
    b.appendChild(note);
  }

  function sumPhases(t, idx) {
    var s = 0, any = false;
    idx.forEach(function (i) { if (t[i] >= 0) { s += t[i]; any = true; } });
    return any ? s + ' ms' : null;
  }

  function openInspectorMenu(r) {
    openSheet(r.method + ' ' + hostOf(r.url), function (sh) {
      sh.appendChild(menuItem('copy', 'Copy URL', null, function () { copyText(r.url); }));
      sh.appendChild(menuItem('copy', 'Copy as cURL', 'Sensitive headers included', function () { copyText(toCurl(r)); }));
      if (!r.pending) sh.appendChild(menuItem('play', 'Replay request…', 'Edit and resend', function () { openReplay(r); }));
      sh.appendChild(menuItem('share', 'Export request', 'JSON, sensitive headers redacted', function () { shareText('request.json', JSON.stringify(recordJson(r), null, 2)); }));
      sh.appendChild(menuItem('share', 'Share as text', null, function () { shareText(r.method + ' ' + hostOf(r.url), requestText(r)); }));
      sh.appendChild(menuItem('file', 'View raw data', 'Everything captured for this request', function () { openRawData(r); }));
      sh.appendChild(menuItem('block', 'Block this URL', null, function () { postBlock(blockUrlOf(r)); }, 'danger'));
      sh.appendChild(menuItem('block', 'Block domain', hostOf(r.url), function () { postBlock(hostOf(r.url)); }, 'danger'));
    });
  }

  function openRawData(r) {
    var ov = overlay('Raw data');
    var copy = iconButton('copy', 'Copy raw data', function () { copyText(txt); });
    ov.el.querySelector('.ovhead').appendChild(copy);
    var copyBody = {};
    for (var k in r) if (Object.prototype.hasOwnProperty.call(r, k)) copyBody[k] = r[k];
    if (copyBody.body && copyBody.body.length > 200000) copyBody.body = copyBody.body.slice(0, 200000) + '…(truncated)';
    var txt = JSON.stringify(copyBody, null, 2);
    var box = el('div', 'codebox');
    var pre = el('pre'); pre.textContent = txt;
    box.appendChild(pre);
    ov.body.appendChild(box);
  }

  // ======================================================================================
  // Network tools: separate pages (Recording, Rules, Statistics, HAR Files) and the overflow menu
  // ======================================================================================
  var activePage = null;
  var harStatus = { state: 'idle', entries: 0, pages: 0, bytes: 0, maxBytes: 0, maxEntries: 0, nearLimit: false, full: false };
  var rulesData = null;
  var PAGE_TITLES = { recording: 'Recording', rules: 'Rules', stats: 'Statistics', hars: 'HAR Files' };

  function openPage(name) {
    if (name === 'requests') { closePage(); return; }
    if (name === 'navigation') { devtools('get-nav'); return; }
    activePage = name;
    netTab.classList.add('tools');
    renderPage();
    if (name === 'rules') devtools('get-rules');
  }
  function closePage() {
    activePage = null;
    netTab.classList.remove('tools');
    document.getElementById('pgOther').replaceChildren();
  }
  function renderPage() {
    if (!activePage) return;
    var pg = document.getElementById('pgOther');
    var bar = el('div', 'appbar');
    var back = iconButton('back', 'Back to requests', closePage);
    bar.appendChild(back);
    bar.appendChild(el('span', 'title', PAGE_TITLES[activePage]));
    var body = el('div', 'scroll');
    var builder = { recording: buildRecordingPage, rules: buildRulesPage, stats: buildStatsPage, hars: buildHarFilesPage }[activePage];
    builder(body, bar);
    pg.replaceChildren(bar, body);
    hydrateIcons(pg);
  }

  function toolRow(icon, label, sub, fn, cls) {
    var r = el('button', 'toolrow' + (cls ? ' ' + cls : ''));
    r.style.width = 'calc(100% - 24px)'; r.style.textAlign = 'left';
    var ic = el('span'); ic.innerHTML = svgIcon(icon); r.appendChild(ic.firstChild);
    r.firstChild.style.cssText = 'width:20px;height:20px;fill:var(--fg-dim);flex-shrink:0';
    var tx = el('span', 'tx', label);
    if (sub) tx.appendChild(el('small', null, sub));
    r.appendChild(tx);
    r.addEventListener('click', fn);
    return r;
  }

  // --- Recording ---
  function buildRecordingPage(body) {
    var h = harStatus;
    var c = card('Session');
    var stateText = h.state === 'recording' ? 'Recording' : h.state === 'paused' ? 'Paused' : 'Not recording';
    c.appendChild(kvRow('State', stateText));
    c.appendChild(kvRow('Requests', String(h.entries) + (h.maxEntries ? ' of ' + h.maxEntries : '')));
    c.appendChild(kvRow('Captured size', formatSize(h.bytes) + (h.maxBytes ? ' of ' + formatSize(h.maxBytes) : '')));
    c.appendChild(kvRow('Pages', String(h.pages)));
    if (h.maxBytes) {
      var tr = el('div', 'bar2'); var track = el('span', 'tr'); var fill = document.createElement('i');
      fill.style.width = Math.min(100, h.bytes / h.maxBytes * 100) + '%';
      if (h.nearLimit || h.full) fill.style.background = 'var(--warn)';
      track.appendChild(fill); tr.appendChild(track); c.appendChild(tr);
    }
    if (h.full) c.appendChild(el('div', 'note err', 'The recording limit was reached.'));
    else if (h.nearLimit) c.appendChild(el('div', 'note', 'Nearly full: save or export the recording soon.'));
    body.appendChild(c);

    var row = el('div', 'row2');
    row.style.margin = '12px 12px 0';
    function btn(label, action, cls) {
      var b = el('button', 'btn' + (cls ? ' ' + cls : ''), label);
      b.addEventListener('click', function () { devtools(action); });
      row.appendChild(b);
    }
    if (h.state === 'idle') btn('Start recording', 'har-start', 'pri');
    else {
      btn(h.state === 'recording' ? 'Pause' : 'Resume', h.state === 'recording' ? 'har-pause' : 'har-resume', 'pri');
      btn('Stop', 'har-stop', 'dng');
    }
    body.appendChild(row);
    body.appendChild(toolRow('file', 'View HAR file', 'Everything recorded so far, with Share and Save', function () { har('all'); }));
    body.appendChild(toolRow('tune', 'Capture settings and presets', 'Resource types, size limits, redaction, export', function () { try { AndroidPanel.postMessage(JSON.stringify({ type: 'har-menu' })); } catch (e) {} }));
    body.appendChild(el('div', 'note', 'Recording continues across navigation, reloads and a lost page process while it is on.')).style.margin = '12px 14px';
  }

  function har(filter) {
    try { AndroidPanel.postMessage(JSON.stringify({ type: 'har-open', filter: filter })); } catch (e) {}
  }
  function currentHarFilter() {
    if (filt.types.size === 0) return 'all';
    return Array.from(filt.types).join(',');
  }

  // --- Statistics (computed from the requests currently in the list) ---
  function buildStatsPage(body, bar) {
    bar.appendChild(el('span', 'spacer'));
    bar.appendChild(iconButton('history', 'Refresh statistics', renderPage));
    var recs = [];
    netList.querySelectorAll('.req').forEach(function (d) { if (d._r) recs.push(d._r); });
    if (!recs.length) { body.appendChild(el('div', 'empty')).innerHTML = '<b>No requests yet</b>Statistics appear as the page loads.'; return; }
    var ok = 0, errs = 0, failed = 0, blocked = 0, pending = 0, bytes = 0, durSum = 0, durN = 0;
    var byType = {};
    recs.forEach(function (r) {
      if (r.pending) pending++;
      else if (isBlocked(r)) blocked++;
      else if (r.failed || r.status === 0 && r.capture !== 'native') failed++;
      else if (r.status >= 400) errs++;
      else if (r.status > 0) ok++;
      bytes += r.size || 0;
      if (r.durationMs) { durSum += r.durationMs; durN++; }
      var t = typeLabel(typeOf(r), r);
      var o = byType[t] || (byType[t] = { n: 0, b: 0 });
      o.n++; o.b += r.size || 0;
    });
    var grid = el('div', 'statgrid');
    [['Total requests', recs.length], ['Successful', ok], ['HTTP errors (4xx/5xx)', errs], ['Failed', failed], ['Blocked', blocked], ['Pending', pending],
     ['Data transferred', formatSize(bytes)], ['Average duration', durN ? Math.round(durSum / durN) + ' ms' : 'n/a']].forEach(function (s) {
      var d = el('div', 'stat'); d.appendChild(el('b', null, String(s[1]))); d.appendChild(el('span', null, s[0])); grid.appendChild(d);
    });
    body.appendChild(grid);

    function topList(title, list, valueOf) {
      var c = card(title);
      list.forEach(function (r) {
        var row = el('div', 'kvr');
        row.appendChild(el('span', 'v', midTruncate(splitUrl(r.url).path, 48)));
        row.appendChild(el('span', 'k', valueOf(r))).style.cssText = 'flex:0 0 auto;text-align:right';
        row.style.cursor = 'pointer';
        row.addEventListener('click', function () {
          var d = netList.querySelector('.req[data-rid="' + r.requestId + '"]');
          if (d) { closePage(); openInspector(d); }
        });
        c.appendChild(row);
      });
      body.appendChild(c);
    }
    topList('Largest responses', recs.filter(function (r) { return r.size; }).sort(function (a, b) { return b.size - a.size; }).slice(0, 5), function (r) { return formatSize(r.size); });
    topList('Slowest requests', recs.filter(function (r) { return r.durationMs; }).sort(function (a, b) { return b.durationMs - a.durationMs; }).slice(0, 5), function (r) { return r.durationMs + ' ms'; });

    var dc = card('Resource distribution');
    var maxN = 0;
    Object.keys(byType).forEach(function (k) { if (byType[k].n > maxN) maxN = byType[k].n; });
    Object.keys(byType).sort(function (a, b) { return byType[b].n - byType[a].n; }).forEach(function (k) {
      var row = el('div', 'bar2');
      row.appendChild(el('span', 'lb', k));
      var track = el('span', 'tr'), fill = document.createElement('i');
      fill.style.width = (byType[k].n / maxN * 100) + '%';
      track.appendChild(fill); row.appendChild(track);
      row.appendChild(el('span', 'vl', byType[k].n + ' · ' + formatSize(byType[k].b)));
      dc.appendChild(row);
    });
    body.appendChild(dc);
  }

  // --- Rules (the real RequestRules list) ---
  function buildRulesPage(body, bar) {
    bar.appendChild(el('span', 'spacer'));
    bar.appendChild(iconButton('more', 'Rule options', function () {
      openSheet('Rules', function (sh) {
        sh.appendChild(menuItem('block', 'Add block rule…', 'Block requests whose URL matches a pattern', addRuleSheet));
        sh.appendChild(menuItem('tune', 'Full rule manager', 'Mock responses, delay and editing', function () { devtools('open-category', { id: 'rules' }); }));
      });
    }));
    if (!rulesData) { body.appendChild(el('div', 'empty')).innerHTML = '<b>Loading rules…</b>'; return; }
    var c = card('Request rules (' + rulesData.rules.length + ')');
    if (!rulesData.rules.length) c.appendChild(el('div', 'kvr')).appendChild(el('span', 'v na', 'No rules yet. Long-press a request to block it, or add a rule from the menu.'));
    rulesData.rules.forEach(function (ru, i) {
      var row = el('div', 'kvr');
      var info = el('span', 'v', ru.pattern);
      info.appendChild(el('div', 'na', (ru.action === 'MOCK' ? 'Mock · HTTP ' + ru.status : 'Block') + (ru.enabled ? '' : ' · disabled'))).style.fontFamily = 'inherit';
      row.appendChild(info);
      var tg = el('button', 'opt' + (ru.enabled ? ' on' : ''), ru.enabled ? 'On' : 'Off');
      tg.addEventListener('click', function () { devtools('rule-toggle', { index: i }); });
      var del = iconButton('close', 'Delete rule', function () { devtools('rule-delete', { index: i }); });
      row.appendChild(tg); row.appendChild(del);
      row.style.alignItems = 'center';
      c.appendChild(row);
    });
    body.appendChild(c);
    if (rulesData.delayMs) body.appendChild(el('div', 'note', 'Network delay: ' + rulesData.delayMs + ' ms (change it in the full rule manager).')).style.margin = '10px 14px';
    body.appendChild(toolRow('tune', 'Full rule manager', 'Mock responses, edit patterns, add delay', function () { devtools('open-category', { id: 'rules' }); }));
  }
  function addRuleSheet() {
    openSheet('Add block rule', function (sh) {
      var input = el('input'); input.type = 'text'; input.placeholder = 'https://example.com/ads/*  or  tracker.net';
      input.style.cssText = 'width:100%;background:var(--surface);border:0;outline:0;color:inherit;padding:12px 14px;border-radius:12px;font:13px var(--mono)';
      sh.appendChild(input);
      var row = el('div', 'row2');
      var add = el('button', 'btn pri', 'Block');
      add.addEventListener('click', function () { var v = input.value.trim(); if (v) { devtools('rule-add', { pattern: v }); closeSheet(); } });
      row.appendChild(add);
      sh.appendChild(row);
      setTimeout(function () { input.focus(); }, 60);
    });
  }
  function showRules(json) {
    try { rulesData = JSON.parse(json); } catch (e) { return; }
    if (activePage === 'rules') renderPage();
  }

  // --- HAR files ---
  function buildHarFilesPage(body) {
    var h = harStatus;
    var c = card('Current recording');
    c.appendChild(kvRow('State', h.state === 'recording' ? 'Recording' : h.state === 'paused' ? 'Paused' : 'Not recording'));
    c.appendChild(kvRow('Requests', String(h.entries)));
    c.appendChild(kvRow('Size', formatSize(h.bytes)));
    body.appendChild(c);
    var oc = card('Open as HAR');
    var opts = el('div', 'opts'); opts.style.padding = '6px 0 8px';
    [['All requests', 'all'], ['Documents', 'doc'], ['Fetch / XHR', 'fetch,xhr'], ['Scripts', 'js'], ['Styles', 'css'], ['Images', 'img'], ['Fonts', 'font'], ['Failed', 'failed']].forEach(function (o) {
      var b = el('button', 'opt', o[0]);
      b.addEventListener('click', function () { har(o[1]); });
      opts.appendChild(b);
    });
    oc.appendChild(opts);
    oc.appendChild(el('div', 'note', 'Uses the recording when there is one, otherwise the requests in the Network tab. The viewer has Share and Save.'));
    body.appendChild(oc);
    body.appendChild(toolRow('tune', 'Export and capture options', 'Save to Downloads, share, presets, limits, clear recording', function () { try { AndroidPanel.postMessage(JSON.stringify({ type: 'har-menu' })); } catch (e) {} }));
    body.appendChild(el('div', 'note', 'Importing and comparing HAR files is not available yet.')).style.margin = '12px 14px';
  }

  function setHarStatus(o) {
    harStatus = o;
    setHarState(o.state);
    if (activePage === 'recording' || activePage === 'hars') renderPage();
  }

  // --- Overflow menu ---
  document.getElementById('nMenuBtn').addEventListener('click', function () {
    openSheet('Network', function (sh) {
      sh.appendChild(el('h4', null, 'Tools'));
      sh.appendChild(menuItem('list', 'Requests', 'The live request list', function () { openPage('requests'); }));
      sh.appendChild(menuItem('rec', 'Recording', 'Start, pause, stop and capture settings', function () { openPage('recording'); }));
      sh.appendChild(menuItem('history', 'Navigation', 'History, redirect chains, loop warnings, protection', function () { openPage('navigation'); }));
      sh.appendChild(menuItem('shield', 'Rules', 'Block and mock requests', function () { openPage('rules'); }));
      sh.appendChild(menuItem('chart', 'Statistics', 'Totals, slowest and largest requests', function () { openPage('stats'); }));
      sh.appendChild(menuItem('file', 'HAR files', 'Open, share and save recordings', function () { openPage('hars'); }));
      sh.appendChild(el('h4', null, 'Actions'));
      sh.appendChild(menuItem('bolt', 'Network events', 'Reloads, redirects, bridge and detection events', function () { devtools('get-events'); }));
      sh.appendChild(menuItem('water', 'Waterfall', null, openWaterfall));
      sh.appendChild(menuItem('link', 'Copy URLs', 'Of the requests currently shown', function () {
        var urls = visibleRecords().map(function (r) { return r.url; });
        if (urls.length) copyText(urls.join('\n'));
      }));
      sh.appendChild(menuItem('download', 'Export HAR', 'For the current filter, with Share and Save', function () { har(currentHarFilter()); }));
      sh.appendChild(menuItem('tune', 'Additional capture controls', 'Resource types, limits, presets, redaction', function () { try { AndroidPanel.postMessage(JSON.stringify({ type: 'har-menu' })); } catch (e) {} }));
    });
  });
  document.getElementById('nClear').addEventListener('click', function () { devtools('clear-network'); });

  // Tablet / landscape: list on the left, inspector on the right.
  function updateWide() {
    var w = netTab.clientWidth || window.innerWidth;
    var wide = w >= 720;
    if (wide === isWide()) return;
    netTab.classList.toggle('wide', wide);
    if (wide) renderInspector();
    else if (!inspected) document.getElementById('inspector').classList.remove('open');
  }
  window.addEventListener('resize', updateWide);
  updateWide();

  hydrateIcons();

  window.__panel = {
    setTheme: setTheme,
    setActiveTab: setActiveTab,
    setSource: setSource,
    clearNetwork: clearNetwork,
    setHarState: setHarState,
    appendNetworkRow: appendNetworkRow,
    deliverBlob: deliverBlob,
    appendConsoleEntry: appendConsoleEntry,
    clearConsole: clearConsole,
    deliverEvalResult: deliverEvalResult,
    deliverReplay: deliverReplay,
    setPrefs: setPrefs,
    markNavigation: markNavigation,
    patchNetworkRow: patchNetworkRow,
    clearForNavigation: clearForNavigation,
    setDevStatus: setDevStatus,
    showNavHistory: showNavHistory,
    showEvents: showEvents,
    showDetection: showDetection,
    showHar: showHar,
    setHarStatus: setHarStatus,
    showRules: showRules
  };
})();
