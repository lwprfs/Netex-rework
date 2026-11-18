// Navigation confirmation helper. Native code decides and shows the dialog; this script only
//  1. reports how a navigation started (Navigation API "navigate" event, meta refresh) so the dialog can name it,
//  2. holds a form submission that would leave the site until the user answers.
// It does not wrap location, history or window.open, and does nothing when the native switch is off.
(function () {
  var ns = window.__netex || (window.__netex = {});
  if (ns.navGuardInstalled) return;
  ns.navGuardInstalled = true;
  if (typeof AndroidNavGuard === 'undefined') return;

  function post(o) {
    try { if (typeof AndroidNetwork !== 'undefined' && AndroidNetwork.postMessage) AndroidNetwork.postMessage(JSON.stringify(o)); } catch (e) {}
  }
  function abs(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return ''; } }
  function userActive() {
    try { return !!(navigator.userActivation && navigator.userActivation.isActive); } catch (e) { return false; }
  }
  var isTop = window === window.top;

  // 1a. Navigation API: location.href / assign / replace, links, forms (Chromium 102+).
  if (isTop && window.navigation && typeof window.navigation.addEventListener === 'function') {
    try {
      window.navigation.addEventListener('navigate', function (e) {
        if (e.navigationType === 'traverse' || e.navigationType === 'reload') return;
        var to = e.destination && e.destination.url;
        if (!to || e.hashChange) return;
        if (to.split('#')[0] === location.href.split('#')[0]) return;
        post({ type: 'nav-hint', kind: e.formData ? 'form' : 'script', url: to, userInitiated: !!e.userInitiated });
      });
    } catch (err) { /* unsupported: native still classifies, just less precisely */ }
  }

  // 1b. Meta refresh.
  function scanMeta() {
    if (!isTop) return;
    var metas = document.querySelectorAll('meta[http-equiv]');
    for (var i = 0; i < metas.length; i++) {
      var m = metas[i];
      if ((m.getAttribute('http-equiv') || '').toLowerCase() !== 'refresh') continue;
      var mt = /^\s*\d+\s*[;,]\s*(?:url\s*=\s*)?['"]?([^'"]+)/i.exec(m.getAttribute('content') || '');
      if (mt && !m.__netexSeen) { m.__netexSeen = 1; post({ type: 'nav-hint', kind: 'meta', url: abs(mt[1].trim()) }); }
    }
  }
  function watchMeta() {
    scanMeta();
    try {
      var head = document.head || document.documentElement;
      var obs = new MutationObserver(scanMeta);
      obs.observe(head, { childList: true });
      setTimeout(function () { try { obs.disconnect(); } catch (e) {} }, 15000);
    } catch (e) {}
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watchMeta, { once: true });
  else watchMeta();

  // 2. Forms that navigate the main frame.
  var held = {};
  var counter = 0;
  var bypass = null;
  document.addEventListener('submit', function (ev) {
    var form = ev.target;
    if (!form || form.tagName !== 'FORM' || ev.defaultPrevented || bypass === form) return;
    var submitter = ev.submitter || null;
    var target = ((submitter && submitter.formTarget) || form.target || '').toLowerCase();
    var mainFrame = isTop && (!target || target === '_self' || target === '_top' || target === '_parent');
    if (!mainFrame) return;
    var action = abs((submitter && submitter.formAction) || form.getAttribute('action') || location.href);
    if (!action || !/^https?:/i.test(action)) return;
    var verdict = 'allow';
    try { verdict = AndroidNavGuard.check(location.href, action, userActive()); } catch (e) {}
    if (verdict !== 'ask') return;
    ev.preventDefault();
    ev.stopImmediatePropagation();
    var id = 'f' + Date.now().toString(36) + (counter++);
    held[id] = { form: form, submitter: submitter };
    var method = ((submitter && submitter.formMethod) || form.method || 'get').toUpperCase();
    post({ type: 'nav-form', id: id, from: location.href, to: action, method: method });
    // Nothing answered (bridge down): do not leave the form stuck forever.
    setTimeout(function () { delete held[id]; }, 120000);
  }, true);

  ns.navResume = function (id, ok) {
    var h = held[id];
    delete held[id];
    if (!h || !ok) return;
    bypass = h.form;
    try {
      if (typeof h.form.requestSubmit === 'function') h.form.requestSubmit(h.submitter || undefined);
      else h.form.submit();
    } catch (e) { try { h.form.submit(); } catch (e2) {} }
    setTimeout(function () { bypass = null; }, 500);
  };
})();
