// Developer Tools compatibility probe.
//  1. Always: tells native code this page's JavaScript context reached the bridge ("hello"), so a
//     destroyed or blocked context is noticed. Safe to run twice (guarded).
//  2. Only when Settings -> Developer Tools allows it for this site: read-only scan of inline scripts,
//     a page-content-removal watcher, and (opt-in "advanced instrumentation") counting of debugger
//     statements in dynamically created code. Nothing is patched unless instrumentation is on.
(function () {
  var ns = window.__netex || (window.__netex = {});
  if (ns.probeInstalled) return;
  ns.probeInstalled = true;
  var isTop = window === window.top;

  function post(o) {
    try {
      if (typeof AndroidNetwork !== 'undefined' && AndroidNetwork.postMessage) {
        AndroidNetwork.postMessage(JSON.stringify(o));
      }
    } catch (e) { /* ignore */ }
  }
  ns.hello = function () { post({ type: 'hello', top: isTop }); };
  ns.hello();
  if (!isTop) return;

  var cfg = null;
  try {
    if (typeof AndroidDevTools !== 'undefined') cfg = JSON.parse(AndroidDevTools.cfg(location.hostname));
  } catch (e) { /* no config: stay passive */ }
  if (!cfg || !(cfg.on || cfg.instrument)) return;

  var sent = {};
  function signal(id, weight, behavioral, detail, count) {
    post({ type: 'devtools-signal', url: location.href, id: id, weight: weight, behavioral: !!behavioral, detail: detail || '', count: count || 1 });
  }
  function signalOnce(id, weight, behavioral, detail) {
    if (sent[id]) return;
    sent[id] = 1;
    signal(id, weight, behavioral, detail, 1);
  }
  function panelOpen() {
    try { return typeof AndroidDevTools !== 'undefined' && AndroidDevTools.panelOpen(); } catch (e) { return false; }
  }

  // ---- 1. read-only inline script scan ----
  function scan() {
    if (!cfg.scan) return;
    var scripts = document.scripts, n = Math.min(scripts.length, 64);
    for (var i = 0; i < n; i++) {
      var s = scripts[i];
      if (s.src) continue;
      var t = (s.textContent || '').slice(0, 200000);
      if (!t) continue;
      if (/disable-devtool|devtools-detector|devtoolsDetector|devtools-detect|DisableDevtool/i.test(t)) signalOnce('devtools-library', 2, false, 'Known detection library in an inline script');
      if (/\bdebugger\b/.test(t) && /(setInterval|setTimeout|constructor\s*\(|Function\s*\()/.test(t)) signalOnce('debugger-inline', 1, false, 'debugger used together with timers or dynamic code');
      if (/outerWidth\s*-\s*innerWidth|outerHeight\s*-\s*innerHeight|innerWidth\s*-\s*outerWidth|innerHeight\s*-\s*outerHeight/.test(t)) signalOnce('viewport-check', 1, false, 'Compares outer and inner window size');
      if (/defineProperty\(\s*\w+\s*,\s*['"]id['"]/.test(t)) signalOnce('console-trap', 1, false, 'Getter trap that fires when the console inspects an element');
      if (/(performance\.now|Date\.now)\(\)[\s\S]{0,200}\bdebugger\b/.test(t)) signalOnce('timing-check', 1, false, 'Measures how long a debugger statement takes');
    }
  }

  // ---- 2. content removal while the panel is open ----
  function watchContent() {
    if (!cfg.behavior || !document.body) return;
    var peak = document.body.children.length;
    var obs;
    try {
      obs = new MutationObserver(function () {
        var c = document.body ? document.body.children.length : 0;
        if (c > peak) peak = c;
        if (peak >= 3 && c * 5 <= peak && panelOpen()) {
          signalOnce('content-cleared', 2, true, 'Page content was removed (' + peak + ' to ' + c + ' elements) while the panel was open');
          obs.disconnect();
        }
      });
      obs.observe(document.body, { childList: true });
      setTimeout(function () { try { obs.disconnect(); } catch (e) {} }, 60000);
    } catch (e) { /* ignore */ }
  }

  // ---- 3. opt-in instrumentation ----
  var dbg = /\bdebugger\b/;
  var counts = {};
  function count(id, detail) {
    var n = counts[id] = (counts[id] || 0) + 1;
    var th = cfg.threshold || 5;
    if (n === 1 || n === th || (n > th && n % (th * 10) === 0)) signal(id, 1, false, detail, n === 1 ? 1 : (n === th ? th - 1 : th * 10));
  }
  function instrument() {
    if (!cfg.instrument) return;
    try {
      var OrigFn = window.Function;
      var Wrapped = function () {
        var args = Array.prototype.slice.call(arguments);
        var last = args.length ? String(args[args.length - 1]) : '';
        if (dbg.test(last)) {
          count('debugger-dynamic', 'debugger in code built with Function()');
          if (cfg.suppress) args[args.length - 1] = last.replace(/\bdebugger\b\s*;?/g, '');
        }
        return OrigFn.apply(this, args);
      };
      Wrapped.prototype = OrigFn.prototype;
      try { OrigFn.prototype.constructor = Wrapped; } catch (e) {}
      window.Function = Wrapped;
    } catch (e) { /* ignore */ }

    var fnSrc = typeof WeakMap === 'function' ? new WeakMap() : null;
    function hasDebugger(fn) {
      if (!fnSrc) return false;
      if (fnSrc.has(fn)) return fnSrc.get(fn);
      var r = false;
      try { var s = Function.prototype.toString.call(fn); r = s.length < 3000 && dbg.test(s); } catch (e) {}
      fnSrc.set(fn, r);
      return r;
    }
    ['setTimeout', 'setInterval'].forEach(function (name) {
      var orig = window[name];
      if (typeof orig !== 'function') return;
      window[name] = function (cb) {
        var args = Array.prototype.slice.call(arguments);
        if (typeof cb === 'string' && dbg.test(cb)) {
          count('debugger-dynamic', 'debugger in a timer string');
          if (cfg.suppress) args[0] = cb.replace(/\bdebugger\b\s*;?/g, '');
        } else if (typeof cb === 'function' && hasDebugger(cb)) {
          args[0] = function () { count('debugger-exec', 'timer callback containing debugger ran'); return cb.apply(this, arguments); };
        }
        return orig.apply(this, args);
      };
    });
    try {
      var origClear = console.clear, clears = [];
      console.clear = function () {
        var now = Date.now();
        clears.push(now);
        while (clears.length && now - clears[0] > 5000) clears.shift();
        if (clears.length >= 10) signalOnce('console-clear', 1, false, 'console.clear() called 10+ times in 5 seconds');
        if (cfg.experimental) return;
        return origClear.apply(this, arguments);
      };
    } catch (e) { /* ignore */ }
    if (cfg.experimental) {
      try { Object.defineProperty(window, 'outerWidth', { get: function () { return window.innerWidth; }, configurable: true }); } catch (e) {}
      try { Object.defineProperty(window, 'outerHeight', { get: function () { return window.innerHeight; }, configurable: true }); } catch (e) {}
    }
  }

  instrument();
  function ready() { scan(); watchContent(); }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready, { once: true });
  else ready();
})();
