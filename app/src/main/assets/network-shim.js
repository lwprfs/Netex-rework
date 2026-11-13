// Patches window.fetch and XMLHttpRequest to forward each request/response
// to the AndroidNetwork bridge. Image/model responses are stashed as Blobs
// in window.__netex.blobs and only encoded to data: URLs lazily when
// the panel asks via window.__netex.findBlob(requestId).
(function () {
  var ns = window.__netex || (window.__netex = {});
  if (ns.installed) return;
  ns.installed = true;

  var MAX_BODY = 256 * 1024;        // 256 KB
  var MAX_BINARY = 4 * 1024 * 1024; // 4 MB
  // Limits for the HAR come from the capture options (set manually in the app, in MB).
  var MAX_BLOBS = 64;               // FIFO cap so long-lived pages don't pin memory

  if (!ns.blobs) ns.blobs = new Map();

  function clip(s) {
    if (typeof s !== 'string') return '';
    return s.length > MAX_BODY ? s.slice(0, MAX_BODY) + '\n…[truncated]' : s;
  }

  function isBinaryDisplayable(contentType) {
    if (!contentType) return false;
    return /^(image|model)\//i.test(contentType);
  }

  function harOn() {
    try { return typeof AndroidHar !== 'undefined' && AndroidHar.on(); } catch (e) { return false; }
  }

  function harOpt(name) {
    try { return typeof AndroidHar !== 'undefined' && AndroidHar.opt(name); } catch (e) { return false; }
  }

  // Size limit in bytes for a HAR option ("bodies", "images", "fonts", "reqbodies").
  function harLimit(name, fallback) {
    try {
      if (typeof AndroidHar !== 'undefined') {
        var v = AndroidHar.limit(name);
        if (typeof v === 'number' && v > 0) return v;
      }
    } catch (e) { /* ignore */ }
    return fallback;
  }

  function absUrl(u) {
    try { return new URL(u, document.baseURI).href; } catch (e) { return u; }
  }

  // Full (untruncated) text for the HAR; only sent when it is longer than what the panel shows.
  function harText(s) {
    if (typeof s !== 'string' || !harOpt('bodies') || s.length <= MAX_BODY) return undefined;
    var max = harLimit('bodies', 8 * 1024 * 1024);
    return s.length > max ? s.slice(0, max) : s;
  }

  // limitName: "images" or "fonts"; blobs above that limit are not inlined.
  function blobToBase64(blob, limitName) {
    return new Promise(function (resolve) {
      if (!blob || blob.size > harLimit(limitName || 'images', 3 * 1024 * 1024)) return resolve(null);
      var r = new FileReader();
      r.onloadend = function () {
        var res = r.result;
        resolve(typeof res === 'string' ? res.slice(res.indexOf(',') + 1) : null);
      };
      r.onerror = function () { resolve(null); };
      r.readAsDataURL(blob);
    });
  }

  function parseLen(headerValue) {
    return parseInt(headerValue || '0', 10) || 0;
  }

  function post(record) {
    try {
      if (typeof AndroidNetwork !== 'undefined' && AndroidNetwork.postMessage) {
        AndroidNetwork.postMessage(JSON.stringify(record));
      }
    } catch (e) { /* ignore */ }
  }

  function newRequestId() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
    // Fallback for older WebViews.
    return 'r-' + Math.random().toString(36).slice(2) + '-' + Date.now().toString(36);
  }

  function captureBlob(rid, blob) {
    if (!blob || blob.size > MAX_BINARY) return;
    if (ns.blobs.size >= MAX_BLOBS) {
      var oldest = ns.blobs.keys().next().value;
      if (oldest) ns.blobs.delete(oldest);
    }
    ns.blobs.set(rid, blob);
  }

  // Extra request-side data used only for HAR export.
  function headerPairs(h) {
    var out = [];
    try {
      if (!h) return out;
      var hs = (typeof Headers !== 'undefined' && h instanceof Headers) ? h : new Headers(h);
      hs.forEach(function (v, k) { out.push([k, v]); });
    } catch (e) { /* ignore */ }
    return out;
  }

  function parseRawHeaders(raw) {
    var out = [];
    (raw || '').trim().split(/[\r\n]+/).forEach(function (line) {
      var i = line.indexOf(':');
      if (i > 0) out.push([line.slice(0, i).trim(), line.slice(i + 1).trim()]);
    });
    return out;
  }

  function capReq(t) {
    var max = harLimit('reqbodies', MAX_BODY);
    return t.length > max ? t.slice(0, max) : t;
  }

  function bodyText(b) {
    if (b == null || !harOn() || !harOpt('reqbodies')) return '';
    if (typeof b === 'string') return capReq(b);
    try {
      if (typeof URLSearchParams !== 'undefined' && b instanceof URLSearchParams) return capReq(b.toString());
      if (typeof FormData !== 'undefined' && b instanceof FormData) {
        var parts = [];
        b.forEach(function (v, k) {
          parts.push(encodeURIComponent(k) + '=' +
            (typeof v === 'string' ? encodeURIComponent(v) : '[file ' + (v && v.name || '') + ']'));
        });
        return capReq(parts.join('&'));
      }
    } catch (e) { /* ignore */ }
    return '';
  }

  function withReq(record, req) {
    record.started = req.started;
    record.reqHeaders = req.headers;
    record.reqBody = req.body;
    record.kind = req.kind || 'fetch';
    return record;
  }

  function postPending(method, url, requestId, duration, size, req) {
    post(withReq({
      method: method.toUpperCase(), url: url, status: 0, body: '',
      contentType: '', duration: duration, size: size,
      requestId: requestId, pending: true
    }, req));
  }

  ns.readBlob = function (rid) {
    var blob = ns.blobs.get(rid);
    if (!blob) {
      post({ type: 'blob-response', requestId: rid, body: '' });
      return;
    }
    var reader = new FileReader();
    reader.onloadend = function () {
      post({ type: 'blob-response', requestId: rid, body: reader.result || '' });
    };
    reader.onerror = function () {
      post({ type: 'blob-response', requestId: rid, body: '' });
    };
    reader.readAsDataURL(blob);
  };

  // Native calls this on a panel expand. Each frame has its own __netex
  // namespace; recurse through same-origin frames to find the one with the blob.
  ns.findBlob = function (rid) {
    function visit(win) {
      try {
        var nsi = win.__netex;
        if (nsi && nsi.blobs && nsi.blobs.has(rid)) {
          nsi.readBlob(rid);
          return true;
        }
        for (var i = 0; i < win.frames.length; i++) {
          if (visit(win.frames[i])) return true;
        }
      } catch (e) { /* cross-origin frame */ }
      return false;
    }
    visit(window);
  };

  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function (input, init) {
      var url = absUrl(typeof input === 'string' ? input : (input && input.url) || '');
      var method = (init && init.method) || (input && input.method) || 'GET';
      var requestId = newRequestId();
      var start = Date.now();
      var req = {
        started: start,
        kind: 'fetch',
        headers: headerPairs(init && init.headers ? init.headers : input && input.headers),
        body: bodyText(init && init.body)
      };
      postPending(method, url, requestId, 0, 0, req);
      var promise = origFetch.apply(this, arguments);
      promise.then(function (response) {
        var ct = response.headers.get('content-type') || '';
        var lenHeader = parseLen(response.headers.get('content-length'));
        var resHeaders = headerPairs(response.headers);
        var emit = function (body, size, extra) {
          var rec = withReq({
            method: method.toUpperCase(),
            url: url,
            status: response.status,
            body: body,
            contentType: ct,
            duration: Date.now() - start,
            size: size || 0,
            requestId: requestId
          }, req);
          rec.resHeaders = resHeaders;
          if (extra) { for (var k in extra) rec[k] = extra[k]; }
          post(rec);
        };
        if (isBinaryDisplayable(ct)) {
          response.clone().blob()
            .then(function (blob) {
              captureBlob(requestId, blob);
              if (!harOpt('images') || !harOpt('kind:image')) return emit('', lenHeader || blob.size, { decoded: blob.size });
              blobToBase64(blob, 'images').then(function (b64) {
                emit('', lenHeader || blob.size,
                  b64 ? { harBody: b64, harB64: true, decoded: blob.size } : { decoded: blob.size });
              });
            })
            .catch(function () { emit('', lenHeader); });
        } else {
          response.clone().text()
            .then(function (b) { emit(clip(b), lenHeader || b.length, { harBody: harText(b), decoded: b.length }); })
            .catch(function () {});
        }
      }).catch(function (err) {
        post(withReq({
          method: method.toUpperCase(),
          url: url,
          status: 0,
          body: '',
          contentType: '',
          duration: Date.now() - start,
          requestId: requestId,
          error: String(err)
        }, req));
      });
      return promise;
    };
  }

  // PerformanceObserver picks up resources the browser loads directly (<img>, <script>,
  // stylesheets, fonts, beacons, the document itself). fetch/XHR are covered above.
  // Panel rows are unchanged (document / iframe / img); scripts, CSS, fonts and beacons are
  // only reported while a HAR recording runs, with full bodies re-read from the HTTP cache.
  var KIND_CT = {
    document: 'text/html', script: 'application/javascript', style: 'text/css',
    image: 'image/*', font: 'font/*', media: '*/*', beacon: '*/*', other: '*/*'
  };

  function kindOf(entry) {
    if (entry.entryType === 'navigation') return 'document';
    var t = entry.initiatorType;
    if (t === 'iframe' || t === 'frame') return 'document';
    if (t === 'fetch') return 'fetch';
    if (t === 'xmlhttprequest') return 'xhr';
    if (t === 'beacon') return 'beacon';
    if (t === 'script') return 'script';
    if (t === 'img' || t === 'image') return 'image';
    if (t === 'audio' || t === 'video' || t === 'track') return 'media';
    var m = String(entry.name || '').split(/[?#]/)[0].match(/\.([a-z0-9]+)$/i);
    var ext = m ? m[1].toLowerCase() : '';
    if (ext === 'css') return 'style';
    if (ext === 'js' || ext === 'mjs') return 'script';
    if (/^(woff2?|ttf|otf|eot)$/.test(ext)) return 'font';
    if (/^(png|jpe?g|gif|webp|avif|svg|bmp|ico)$/.test(ext)) return 'image';
    if (/^(mp4|webm|ogg|mp3|wav|m4a|aac|mov)$/.test(ext)) return 'media';
    return 'other';
  }

  // Panel rows: documents, frames and images are always reported (as before). Every other
  // type is only reported while a HAR recording runs and that type is switched on.
  function classify(entry) {
    if (entry.entryType !== 'navigation' && entry.entryType !== 'resource') return null;
    var kind = kindOf(entry);
    if (kind === 'fetch' || kind === 'xhr') return null; // covered by the fetch / XHR patches
    if (kind !== 'document' && kind !== 'image' && !harOpt('kind:' + kind)) return null;
    return KIND_CT[kind];
  }

  function isUninteresting(entry) {
    var url = entry.name || '';
    if (url.indexOf('data:') === 0) return true;
    if (url.indexOf('about:') === 0) return true;
    if (url.indexOf('javascript:') === 0) return true;
    if (url.indexOf('blob:') === 0) return true;
    return false;
  }

  function perfTimings(e) {
    // HAR order: blocked, dns, connect, ssl, send, wait, receive (-1 = not applicable).
    function d(a, b) { return (a > 0 && b >= a) ? Math.round(b - a) : -1; }
    var wait = e.responseStart > 0 && e.requestStart > 0 ? Math.round(e.responseStart - e.requestStart) : -1;
    var recv = e.responseEnd > 0 && e.responseStart > 0 ? Math.round(e.responseEnd - e.responseStart) : -1;
    var blocked = e.fetchStart >= 0 && e.domainLookupStart > e.fetchStart ? Math.round(e.domainLookupStart - e.fetchStart) : -1;
    var ssl = e.secureConnectionStart > 0 ? d(e.secureConnectionStart, e.connectEnd) : -1;
    var connect = d(e.connectStart, e.connectEnd);
    if (ssl >= 0 && connect >= 0) connect = Math.max(0, connect - ssl);
    return [blocked, d(e.domainLookupStart, e.domainLookupEnd), connect, ssl,
            (e.requestStart > 0 && e.connectEnd > 0) ? Math.max(0, Math.round(e.requestStart - e.connectEnd)) : 0,
            wait, recv];
  }

  // Cap concurrent cache re-reads so a busy page can't flood the network / bridge.
  var inflight = 0, queue = [];
  function pump() {
    while (inflight < 4 && queue.length) {
      inflight++;
      queue.shift()(function () { inflight--; pump(); });
    }
  }

  function wantsBody(entry, ct) {
    if (!origFetch) return false;
    var kind = kindOf(entry);
    if (kind === 'image') return harOpt('images') && harOpt('kind:image');
    if (!harOpt('kind:' + kind)) return false;
    if (kind === 'font') return harOpt('fonts');
    if (kind === 'script' || kind === 'style') return harOpt('bodies');
    return kind === 'other' && harOpt('bodies') && /\.(css|js|mjs|json|svg)(\?|#|$)/i.test(entry.name);
  }

  function postEntry(entry, ct, base) {
    var rec = {
      method: 'GET', url: entry.name, status: entry.responseStatus || 0,
      body: '', contentType: ct, duration: Math.round(entry.duration || 0),
      size: entry.decodedBodySize || entry.encodedBodySize || 0, requestId: '',
      started: Math.round((performance.timeOrigin || 0) + entry.startTime),
      encoded: entry.encodedBodySize || 0, decoded: entry.decodedBodySize || 0,
      transfer: entry.transferSize || 0, protocol: entry.nextHopProtocol || '',
      timings: perfTimings(entry), kind: kindOf(entry)
    };
    if (base) for (var k in base) rec[k] = base[k];
    post(rec);
  }

  var seenEntries = new Set();
  try {
    var po = new PerformanceObserver(function (list) {
      list.getEntries().forEach(function (entry) {
        if (isUninteresting(entry)) return;
        // Sub-frames' navigation is already logged by the parent as an iframe resource.
        if (entry.entryType === 'navigation' && window !== window.top) return;
        var ct = classify(entry);
        if (!ct) return;
        var key = entry.entryType + '|' + entry.startTime + '|' + entry.name;
        if (seenEntries.has(key)) return;
        seenEntries.add(key);
        if (seenEntries.size > 512) seenEntries.clear();
        var body = '', full;
        if (entry.entryType === 'navigation' && window === window.top) {
          try {
            var html = document.documentElement.outerHTML;
            body = clip(html);
            full = harText(html);
          } catch (e) {}
        }
        var base = {};
        if (body) base.body = body;
        if (full !== undefined) base.harBody = full;
        if (!(entry.entryType === 'resource' && harOn() && wantsBody(entry, ct))) {
          return postEntry(entry, ct, base);
        }
        // Re-read the (cached) response to get headers + body for the HAR.
        queue.push(function (done) {
          origFetch(entry.name, { cache: 'force-cache', credentials: 'omit' }).then(function (res) {
            var rct = res.headers.get('content-type') || ct;
            base.resHeaders = headerPairs(res.headers);
            base.contentType = rct;
            base.status = res.status || entry.responseStatus || 0;
            if (/^(image|font)\//i.test(rct) || /octet-stream|woff/i.test(rct)) {
              var limitName = /^image\//i.test(rct) ? 'images' : 'fonts';
              return res.blob().then(function (bl) { return blobToBase64(bl, limitName); }).then(function (b64) {
                if (b64) { base.harBody = b64; base.harB64 = true; }
              });
            }
            return res.text().then(function (t) {
              base.body = clip(t);
              var h = harText(t); base.harBody = h !== undefined ? h : t;
              base.harB64 = false;
              base.decoded = t.length;
            });
          }).catch(function () { /* CORS-blocked: keep metadata only */ })
            .then(function () { postEntry(entry, base.contentType || ct, base); done(); });
        });
        pump();
      });
    });
    // Two observes (each with buffered:true) — entries finalized before the async callback
    // runs would be lost otherwise. Dedupe handles any double-delivery.
    po.observe({ type: 'resource', buffered: true });
    po.observe({ type: 'navigation', buffered: true });
  } catch (e) { /* PerformanceObserver unavailable */ }

  var XHR = window.XMLHttpRequest;
  if (XHR) {
    var origOpen = XHR.prototype.open;
    var origSend = XHR.prototype.send;
    XHR.prototype.open = function (method, url) {
      this._netMethod = method;
      this._netUrl = url;
      return origOpen.apply(this, arguments);
    };
    var origSetHeader = XHR.prototype.setRequestHeader;
    XHR.prototype.setRequestHeader = function (k, v) {
      (this._netHeaders || (this._netHeaders = [])).push([k, v]);
      return origSetHeader.apply(this, arguments);
    };
    XHR.prototype.send = function (sendBody) {
      var xhr = this;
      var method = xhr._netMethod || 'GET';
      var url = absUrl(xhr._netUrl || '');
      var requestId = newRequestId();
      var start = Date.now();
      var req = { started: start, kind: 'xhr', headers: xhr._netHeaders || [], body: bodyText(sendBody) };
      postPending(method, url, requestId, 0, 0, req);
      // Throttle to ~4/s so big downloads (e.g. 12 MB STL) don't flood the bridge.
      var lastProg = 0;
      xhr.addEventListener('progress', function (e) {
        var now = Date.now();
        if (now - lastProg < 250) return;
        lastProg = now;
        postPending(method, url, requestId, now - start, e.loaded || 0, req);
      });
      xhr.addEventListener('loadend', function () {
        var ct = xhr.getResponseHeader('content-type') || '';
        var lenHeader = parseLen(xhr.getResponseHeader('content-length'));
        var body = '';
        if (!isBinaryDisplayable(ct)) {
          try { body = xhr.responseText || ''; } catch (e) {}
        }
        var fullHar = harText(body);
        var rec = withReq({
          method: method.toUpperCase(),
          url: url,
          status: xhr.status,
          body: clip(body),
          contentType: ct,
          duration: Date.now() - start,
          size: lenHeader || body.length || 0,
          requestId: requestId
        }, req);
        rec.resHeaders = parseRawHeaders(xhr.getAllResponseHeaders());
        if (fullHar !== undefined) rec.harBody = fullHar;
        rec.decoded = body.length;
        post(rec);
      });
      return origSend.apply(this, arguments);
    };
  }
})();
