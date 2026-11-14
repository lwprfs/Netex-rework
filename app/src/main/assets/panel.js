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
  var netFilter = 'all';
  var netQuery = '';
  var netList = document.getElementById('netList');
  // Rows live in their own scroll container. Pin to bottom unless the user scrolled up.
  var networkScrolledUp = false;
  netList.addEventListener('scroll', function () {
    networkScrolledUp = (netList.scrollTop + netList.clientHeight) < (netList.scrollHeight - 4);
  }, { passive: true });

  function updateTotals() {
    document.getElementById('netTotalsCount').textContent = totalCount === 0 ? '' :
      totalCount + ' request' + (totalCount === 1 ? '' : 's');
    document.getElementById('netTotalsSize').textContent = totalCount === 0 ? '' : formatSize(totalBytes);
  }

  function setHarState(state) {
    var btn = document.getElementById('harBtn');
    btn.className = 'har-btn' + (state === 'idle' ? '' : ' ' + state);
    btn.textContent = state === 'recording' ? '● REC' : state === 'paused' ? '❚❚ HAR' : 'HAR';
  }

  document.getElementById('harBtn').addEventListener('click', function () {
    try { AndroidPanel.postMessage(JSON.stringify({ type: 'har-menu' })); } catch (e) {}
  });

  function typeOf(r) {
    var ct = (r.contentType || '').toLowerCase(), u = r.url || '';
    if (/^image\//.test(ct) || /\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)(\?|#|$)/i.test(u)) return 'img';
    if (ct.indexOf('html') !== -1) return 'doc';
    if (ct.indexOf('javascript') !== -1 || ct.indexOf('ecmascript') !== -1 || /\.m?js(\?|#|$)/i.test(u)) return 'js';
    if (ct.indexOf('css') !== -1 || /\.css(\?|#|$)/i.test(u)) return 'css';
    if (ct.indexOf('json') !== -1 || ct.indexOf('xml') !== -1 || ct.indexOf('text/') === 0) return 'data';
    return 'other';
  }

  var netTerms = [];

  function applyNetFilter(det) {
    var d = det.dataset;
    var okType;
    if (netFilter === 'all') okType = true;
    else if (netFilter === 'err') okType = d.err === '1';
    else if (netFilter === 'slow') okType = d.slow === '1';
    else if (netFilter === 'post') okType = d.write === '1';
    else okType = d.type === netFilter;
    // Every space-separated word must appear in "method status url type content-type".
    var okText = true;
    var hay = d.hay || '';
    for (var i = 0; i < netTerms.length; i++) {
      if (hay.indexOf(netTerms[i]) === -1) { okText = false; break; }
    }
    det.classList.toggle('hide', !(okType && okText));
  }

  function refilterNetwork() {
    netList.querySelectorAll('details.req').forEach(applyNetFilter);
  }

  document.getElementById('netChips').addEventListener('click', function (e) {
    var chip = e.target.closest('.chip');
    if (!chip) return;
    netFilter = chip.dataset.t;
    this.querySelectorAll('.chip').forEach(function (c) { c.classList.toggle('on', c === chip); });
    refilterNetwork();
  });
  document.getElementById('netSearch').addEventListener('input', function () {
    netQuery = this.value.trim().toLowerCase();
    netTerms = netQuery ? netQuery.split(/\s+/) : [];
    refilterNetwork();
  });

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
    updateTotals();
  }

  function appendNetworkRow(r) {
    var container = netList;
    var det = r.requestId
      ? container.querySelector('details.req[data-rid="' + r.requestId + '"]')
      : null;
    var isNew = !det;

    if (isNew) {
      var empty = container.querySelector('.empty');
      if (empty) empty.remove();
      det = document.createElement('details');
      det.className = 'req';
      if (r.requestId) det.dataset.rid = r.requestId;
      det.dataset.lastSize = '0';
      container.appendChild(det);
      totalCount++;
      bindToggle(det, r.requestId);
    }

    // Track size delta per row so progress ticks update totals without double-counting.
    var lastSize = parseInt(det.dataset.lastSize, 10) || 0;
    var newSize = r.size || 0;
    totalBytes += newSize - lastSize;
    det.dataset.lastSize = String(newSize);
    updateTotals();

    renderRow(det, r);

    // Scroll after renderRow so scrollHeight reflects the new row's content.
    if (isNew && !networkScrolledUp) netList.scrollTop = netList.scrollHeight;
  }

  function shellQuote(str) {
    return "'" + String(str).replace(/'/g, "'\\''") + "'";
  }

  function toCurl(r) {
    var method = (r.method || 'GET').toUpperCase();
    var out = 'curl ' + shellQuote(r.url);
    if (method !== 'GET') out += ' -X ' + method;
    (r.reqHeaders || []).forEach(function (h) {
      if (h[0] && h[0].charAt(0) !== ':') out += ' -H ' + shellQuote(h[0] + ': ' + h[1]);
    });
    if (r.reqBody) out += ' --data-raw ' + shellQuote(r.reqBody);
    return out;
  }

  // Sends the same request again from the page itself (so cookies and origin match); the new
  // request shows up in this list like any other.
  function replayRequest(r) {
    var method = (r.method || 'GET').toUpperCase();
    var headers = {};
    (r.reqHeaders || []).forEach(function (h) {
      if (/^[A-Za-z0-9!#$%&'*+.^_`|~-]+$/.test(h[0] || '')) headers[h[0]] = h[1];
    });
    var opts = { method: method, headers: headers, credentials: 'include' };
    if (r.reqBody && method !== 'GET' && method !== 'HEAD') opts.body = r.reqBody;
    var source = '(fetch(' + JSON.stringify(r.url) + ',' + JSON.stringify(opts) + ').catch(function(){}),0)';
    try {
      AndroidPanel.postMessage(JSON.stringify({ type: 'eval', evalId: 'replay-' + Date.now(), source: source }));
    } catch (e) {}
  }

  function renderRow(det, r) {
    var statusClass = (r.pending || r.status === 0) ? 's0'
      : r.status < 300 ? 's2xx'
      : r.status < 400 ? 's3xx'
      : r.status < 500 ? 's4xx'
      : 's5xx';
    var statusText = r.pending ? '…' : r.status === 0 ? '—' : r.status;
    var type = typeOf(r);
    det.dataset.type = type;
    det.dataset.url = r.url || '';
    det.dataset.err = (!r.pending && (r.status >= 400 || r.status === 0)) ? '1' : '0';
    det.dataset.slow = (!r.pending && r.durationMs > 1000) ? '1' : '0';
    det.dataset.write = ['POST', 'PUT', 'PATCH', 'DELETE'].indexOf((r.method || '').toUpperCase()) !== -1 ? '1' : '0';
    det.dataset.hay = ((r.method || '') + ' ' + (r.pending ? '' : r.status) + ' ' + (r.url || '') + ' ' + type + ' ' + (r.contentType || '')).toLowerCase();

    var summary = document.createElement('summary');
    summary.appendChild(span('method ' + r.method, r.method));
    summary.appendChild(span('url', r.displayUrl || r.url));
    if (r.size) summary.appendChild(span('size', formatSize(r.size)));
    summary.appendChild(span('status ' + statusClass, statusText));

    var body = document.createElement('div');
    body.className = 'body';

    var meta = document.createElement('div');
    meta.className = 'meta';
    meta.appendChild(span('tag', r.pending ? 'Pending' : (r.status ? 'HTTP ' + r.status : 'No response')));
    meta.appendChild(span('tag', type.toUpperCase()));
    if (r.contentType) meta.appendChild(span('tag', r.contentType.split(';')[0]));
    if (r.durationMs) meta.appendChild(span('tag', r.durationMs + ' ms'));
    if (r.size) meta.appendChild(span('tag', formatSize(r.size)));
    body.appendChild(meta);
    body.appendChild(span('fullurl', r.url));

    var actions = document.createElement('div');
    actions.className = 'actions';
    var copyUrl = document.createElement('button');
    copyUrl.textContent = 'Copy URL';
    copyUrl.addEventListener('click', function (e) { e.stopPropagation(); copyText(r.url); });
    actions.appendChild(copyUrl);
    var copyCurl = document.createElement('button');
    copyCurl.textContent = 'Copy cURL';
    copyCurl.addEventListener('click', function (e) { e.stopPropagation(); copyText(toCurl(r)); });
    actions.appendChild(copyCurl);
    if (!r.pending) {
      var replayBtn = document.createElement('button');
      replayBtn.textContent = 'Replay';
      replayBtn.addEventListener('click', function (e) { e.stopPropagation(); replayRequest(r); });
      actions.appendChild(replayBtn);
    }
    if (!r.pending && r.body) {
      var copyBody = document.createElement('button');
      copyBody.textContent = 'Copy body';
      copyBody.addEventListener('click', function (e) { e.stopPropagation(); copyText(r.body); });
      actions.appendChild(copyBody);
    }
    body.appendChild(actions);
    if (!r.pending) body.appendChild(buildMedia(r) || buildText(r));

    det.replaceChildren(summary, body);
    applyNetFilter(det);
  }

  function bindToggle(det, rid) {
    if (!rid) return;
    det.addEventListener('toggle', function () {
      if (!det.open || det.dataset.loaded) return;
      var media = det.querySelector(':scope > .body > img, :scope > .body > model-viewer');
      if (!media) return;
      det.dataset.loaded = '1';
      requestBlob(rid, function (dataUrl) {
        if (dataUrl) media.src = dataUrl;
      });
    });
  }

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

  function appendConsoleEntry(r) {
    var entries = document.getElementById('consoleEntries');
    var div = document.createElement('div');
    div.className = 'entry ' + (r.level || 'log');

    var glyph = document.createElement('span');
    glyph.className = 'glyph';
    glyph.textContent = GLYPH[r.level] || '';
    div.appendChild(glyph);

    var msg = document.createElement('span');
    msg.className = 'msg';
    if (Array.isArray(r.segments)) {
      r.segments.forEach(function (s) {
        var seg = document.createElement('span');
        seg.textContent = s.text || '';
        if (s.style) seg.style.cssText = sanitizeStyle(s.style);
        msg.appendChild(seg);
      });
    } else {
      msg.textContent = r.message || '';
    }
    div.appendChild(msg);

    if (r.sourceId) {
      var src = document.createElement('span');
      src.className = 'src';
      src.textContent = shortenSource(r.sourceId) + (r.line ? ':' + r.line : '');
      div.appendChild(src);
    }

    entries.appendChild(div);
    while (entries.childElementCount > MAX_CONSOLE_ENTRIES) entries.firstElementChild.remove();
    if (r.level === 'warn') consoleWarnCount++;
    else if (r.level === 'error' || r.level === 'result_error') consoleErrorCount++;
    else if (r.level !== 'input' && r.level !== 'result') consoleLogCount++;
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
    if (!source) return;
    // Pin to bottom when the user runs a command — they want to see the result, even
    // if they had scrolled up to inspect earlier logs.
    consoleScrolledUp = false;
    history.push(source);
    if (history.length > MAX_HISTORY) history.shift();
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

  document.getElementById('consoleBar').addEventListener('click', function (e) {
    var chip = e.target.closest('.chip');
    if (!chip) return;
    document.getElementById('consoleEntries').dataset.f = chip.dataset.f;
    this.querySelectorAll('.chip').forEach(function (c) { c.classList.toggle('on', c === chip); });
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

  function wireConsolePrompt() {
    var form = document.getElementById('consolePrompt');
    var input = document.getElementById('consoleInput');
    if (!form || !input) return;
    form.addEventListener('submit', function (e) {
      e.preventDefault();
      var src = input.value;
      input.value = '';
      submitEval(src);
    });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'ArrowUp') {
        if (history.length === 0) return;
        if (historyCursor === -1) draft = input.value;
        historyCursor = historyCursor === -1 ? history.length - 1 : Math.max(0, historyCursor - 1);
        input.value = history[historyCursor];
        e.preventDefault();
      } else if (e.key === 'ArrowDown') {
        if (historyCursor === -1) return;
        historyCursor = historyCursor + 1;
        if (historyCursor >= history.length) {
          historyCursor = -1;
          input.value = draft;
        } else {
          input.value = history[historyCursor];
        }
        e.preventDefault();
      }
    });
  }
  wireConsolePrompt();

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
    deliverEvalResult: deliverEvalResult
  };
})();
