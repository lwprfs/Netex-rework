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
  var netMethod = 'all';
  var netStatus = 'all';
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
    if (/^(video|audio)\//.test(ct) || /\.(mp4|webm|m4a|mp3|ogg|wav|m3u8|mpd)(\?|#|$)/i.test(u)) return 'media';
    if (/^font\//.test(ct) || ct.indexOf('woff') !== -1 || /\.(woff2?|ttf|otf|eot)(\?|#|$)/i.test(u)) return 'font';
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
    else if (netFilter === 'fetch' || netFilter === 'xhr') okType = d.kind === netFilter;
    else okType = d.type === netFilter;
    if (okType && netMethod !== 'all') okType = d.method === netMethod;
    if (okType && netStatus !== 'all') {
      if (netStatus === 'failed') okType = d.failed === '1';
      else okType = d.sc === netStatus;
    }
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
    if (chip.dataset.t === 'more') {
      var more = document.getElementById('netMore');
      var open = more.style.display === 'none';
      more.style.display = open ? '' : 'none';
      chip.textContent = open ? 'Filters ▴' : 'Filters ▾';
      return;
    }
    netFilter = chip.dataset.t;
    this.querySelectorAll('.chip').forEach(function (c) { c.classList.toggle('on', c === chip); });
    refilterNetwork();
  });
  document.getElementById('netMethods').addEventListener('click', function (e) {
    var chip = e.target.closest('.chip');
    if (!chip) return;
    netMethod = chip.dataset.m;
    this.querySelectorAll('.chip').forEach(function (c) { c.classList.toggle('on', c === chip); });
    refilterNetwork();
  });
  document.getElementById('netStatuses').addEventListener('click', function (e) {
    var chip = e.target.closest('.chip');
    if (!chip) return;
    netStatus = chip.dataset.s;
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
    det._r = r;
    det.dataset.kind = r.kind || '';
    det.dataset.method = (r.method || '').toUpperCase();
    det.dataset.sc = (!r.pending && r.status >= 200) ? String(Math.floor(r.status / 100)) : '';
    det.dataset.failed = (!r.pending && r.status === 0) ? '1' : '0';
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

    body.appendChild(buildActions(r));
    body.appendChild(buildSections(r));

    det.replaceChildren(summary, body);
    applyNetFilter(det);
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

  function buildActions(r) {
    var a = el('div', 'actions');
    a.appendChild(actionButton('Copy URL', function () { copyText(r.url); }));
    a.appendChild(actionButton('Copy cURL', function () { copyText(toCurl(r)); }));
    if (r.reqHeaders && r.reqHeaders.length) a.appendChild(actionButton('Req headers', function () { copyText(headersText(r.reqHeaders)); }));
    if (r.resHeaders && r.resHeaders.length) a.appendChild(actionButton('Res headers', function () { copyText(headersText(r.resHeaders)); }));
    if (r.reqBody) a.appendChild(actionButton('Payload', function () { copyText(r.reqBody); }));
    if (!r.pending && r.body) a.appendChild(actionButton('Response', function () { copyText(r.body); }));
    a.appendChild(actionButton('Share', function () { shareText(r.method + ' ' + hostOf(r.url), requestText(r)); }));
    a.appendChild(actionButton('Export', function () { shareText('request.json', JSON.stringify(recordJson(r), null, 2)); }));
    a.appendChild(actionButton('Block URL', function () {
      try { var x = new URL(r.url); postBlock(x.origin + x.pathname); } catch (e) { postBlock(r.url); }
    }));
    a.appendChild(actionButton('Block domain', function () { postBlock(hostOf(r.url)); }));
    if (!r.pending) a.appendChild(actionButton('Replay…', function () { openReplay(r); }));
    return a;
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

  function payloadView(r) {
    var raw = r.reqBody || '';
    if (!raw) return el('div', 'pad jdim', 'No request payload');
    var j = tryJson(raw);
    if (j.ok) return jsonViewer(j.value);
    var ct = '';
    (r.reqHeaders || []).forEach(function (h) { if (/^content-type$/i.test(h[0])) ct = h[1]; });
    if (/x-www-form-urlencoded/i.test(ct)) {
      var pairs = [];
      try { new URLSearchParams(raw).forEach(function (v, k) { pairs.push([k, v]); }); return kvTable(pairs, ''); } catch (e) {}
    }
    var pre = el('pre');
    pre.textContent = raw;
    return pre;
  }

  function responseView(r) {
    var media = buildMedia(r);
    if (media) return media;
    var ct = (r.contentType || '').toLowerCase();
    var raw = r.body || '';
    if (!raw) {
      return el('div', 'pad jdim', r.size ? 'No text body captured (binary or too large) · ' + formatSize(r.size) : 'Empty response');
    }
    var j = tryJson(raw);
    if (j.ok) return jsonViewer(j.value);
    var wrap = el('div');
    if (ct.indexOf('html') !== -1) {
      var btn = el('button', 'prevbtn', 'Preview page');
      var frame = null;
      btn.addEventListener('click', function (e) {
        e.stopPropagation();
        if (frame) { frame.remove(); frame = null; btn.textContent = 'Preview page'; return; }
        frame = document.createElement('iframe');
        frame.className = 'hp';
        frame.setAttribute('sandbox', ''); // no scripts, forms or navigation
        frame.srcdoc = raw;
        wrap.insertBefore(frame, btn.nextSibling);
        btn.textContent = 'Hide preview';
      });
      wrap.appendChild(btn);
    }
    wrap.appendChild(buildText(r));
    return wrap;
  }

  function timingView(r) {
    var box = el('div');
    var rows = [];
    if (r.startedAt) rows.push(['Started', hms(r.startedAt)]);
    rows.push(['Total', r.durationMs ? r.durationMs + ' ms' : 'not reported']);
    var t = r.timings;
    var names = ['Blocked / queued', 'DNS lookup', 'Connect', 'TLS handshake', 'Request sent', 'Waiting (first byte)', 'Content download'];
    var segs = [];
    if (t && t.length >= 7) names.forEach(function (n, i) { if (t[i] >= 0) segs.push([n, t[i], i]); });
    if (segs.length) {
      var total = segs.reduce(function (a, s) { return a + s[1]; }, 0) || 1;
      var bar = el('div', 'tbar');
      segs.forEach(function (s) {
        var d = el('span', 'tseg t' + s[2]);
        d.style.width = Math.max(1, s[1] / total * 100) + '%';
        bar.appendChild(d);
      });
      box.appendChild(bar);
      segs.forEach(function (s) { rows.push([s[0], s[1] + ' ms']); });
    } else {
      rows.push(['Phases', 'Not reported by the page for this request']);
    }
    box.appendChild(kvTable(rows, ''));
    return box;
  }

  function buildSections(r) {
    var box = el('div');
    var qp = queryPairs(r.url);
    if (r.reqHeaders && r.reqHeaders.length) box.appendChild(section('Request headers (' + r.reqHeaders.length + ')', function () { return kvTable(r.reqHeaders, ''); }));
    if (r.resHeaders && r.resHeaders.length) box.appendChild(section('Response headers (' + r.resHeaders.length + ')', function () { return kvTable(r.resHeaders, ''); }));
    if (qp.length) box.appendChild(section('Query parameters (' + qp.length + ')', function () { return kvTable(qp, ''); }));
    if (r.reqBody) box.appendChild(section('Request payload', function () { return payloadView(r); }));
    if (!r.pending) box.appendChild(section('Response', function () { return responseView(r); }, true));
    box.appendChild(section('Timing', function () { return timingView(r); }));
    return box;
  }

  // ======================================================================================
  // Network: overlays, waterfall, edit & replay
  // ======================================================================================
  function overlay(title) {
    var ov = el('div', 'ov');
    var head = el('div', 'ovhead');
    head.appendChild(el('b', null, title));
    head.appendChild(el('span', 'spacer'));
    var close = el('button', 'chip', 'Close');
    close.addEventListener('click', function () { ov.remove(); });
    head.appendChild(close);
    var body = el('div', 'ovbody');
    ov.appendChild(head);
    ov.appendChild(body);
    document.getElementById('network-tab').appendChild(ov);
    return { el: ov, body: body };
  }

  function visibleRecords() {
    var out = [];
    netList.querySelectorAll('details.req').forEach(function (d) {
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
    savePref('keep_network', keepNetwork);
  });
  document.getElementById('nWater').addEventListener('click', openWaterfall);
  document.getElementById('nCopyUrls').addEventListener('click', function () {
    var urls = visibleRecords().map(function (r) { return r.url; });
    if (urls.length) copyText(urls.join('\n'));
  });
  document.getElementById('nExport').addEventListener('click', function () {
    var rows = visibleRecords();
    if (rows.length) shareText('Netex requests (' + rows.length + ')', JSON.stringify(rows.map(recordJson), null, 2));
  });

  function bindToggle(det, rid) {
    if (!rid) return;
    det.addEventListener('toggle', function () {
      if (!det.open || det.dataset.loaded || det.dataset.stale) return;
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

  document.getElementById('consoleBar').addEventListener('click', function (e) {
    var chip = e.target.closest('.chip');
    if (!chip) return;
    consoleFilter = chip.dataset.f;
    this.querySelectorAll('.chip').forEach(function (c) { c.classList.toggle('on', c === chip); });
    refilterConsole();
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
    if (Array.isArray(o.history)) {
      history = o.history.filter(function (x) { return typeof x === 'string'; }).slice(-MAX_HISTORY);
      historyCursor = -1;
    }
  }

  // Preserve-log mode: old rows stay, a divider marks the navigation.
  function markNavigation(kind, url) {
    if (kind === 'console') {
      var entries = document.getElementById('consoleEntries');
      entries.appendChild(el('div', 'sep', 'Navigated to ' + url));
      if (!consoleScrolledUp) entries.scrollTop = entries.scrollHeight;
    } else {
      var rows = netList.querySelectorAll('details.req');
      if (!rows.length) return;
      rows.forEach(function (d) {
        delete d.dataset.rid; // new requests reuse ids; never merge into a previous page's row
        d.dataset.stale = '1';
        d.classList.add('old');
      });
      netList.appendChild(el('div', 'navsep', 'Navigated to ' + url));
    }
  }

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
    markNavigation: markNavigation
  };
})();
