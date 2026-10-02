/* FocusLock pattern matcher — shared by SW, popup, options, content guard.
   Supported patterns (one per line in UI):
     youtube.com            -> matches youtube.com + any subdomain + any path
     *.youtube.com          -> any subdomain (and root)
     reddit.com/r/*         -> path wildcard
     *keyword*              -> URL substring keyword block
     https://example.com/x  -> full-URL prefix
     /regex-body/flags?     -> raw regex (advanced, e.g. /twitch\.tv\/videos/i)
*/
(function (root) {
  function normalizeUrl(urlStr) {
    try {
      const u = new URL(urlStr);
      return {
        ok: true,
        protocol: u.protocol,
        host: u.hostname.toLowerCase().replace(/\.$/, ''),
        path: (u.pathname + u.search).toLowerCase(),
        full: urlStr.toLowerCase()
      };
    } catch (e) {
      return { ok: false };
    }
  }

  function isInternalUrl(urlStr) {
    return /^(chrome:|chrome-extension:|edge:|about:|brave:|opera:|view-source:)/i.test(urlStr || '');
  }

  function domainOf(urlStr) {
    try { return new URL(urlStr).hostname.toLowerCase().replace(/\.$/, '').replace(/^www\./, ''); }
    catch (e) { return ''; }
  }

  // Shared by the Boundaries "Add website" dialog and the Permalock panel.
  // Accepts a domain or URL, returns { value: host } or { error: message }.
  function parseDomainInput(raw) {
    let v = String(raw || '').trim().toLowerCase();
    if (!v) return { error: 'Enter a website.' };
    if (/^(chrome|chrome-extension|edge|about|javascript|data|file):/i.test(v)) return { error: 'That address cannot be blocked.' };
    v = v.replace(/^https?:\/\//, '').replace(/^www\./, '');
    const host = v.split('/')[0].split('?')[0].replace(/:\d+$/, '');
    if (!host) return { error: 'Enter a valid domain.' };
    if (!/^(\*\.)?[a-z0-9][a-z0-9-]*(\.[a-z0-9][a-z0-9-]*)+$/.test(host)) {
      return { error: 'Use a domain like example.com — wildcards like *.example.com are allowed.' };
    }
    return { value: host };
  }


  function escapeReg(s) {
    return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  // Compile a single user pattern to a test function
  function compilePattern(raw) {
    if (typeof raw !== 'string' || raw.length > 2000) return null;
    let p = raw.trim();
    if (!p || p.startsWith('#')) return null;
    // raw regex: /body/flags
    if (p.length > 2 && p.startsWith('/') && p.lastIndexOf('/') > 0) {
      const last = p.lastIndexOf('/');
      try {
        const re = new RegExp(p.slice(1, last), p.slice(last + 1) || 'i');
        // Global/sticky regexes mutate lastIndex. Every verdict must start at
        // the same position, including repeated visits to the same URL.
        return { raw, test: (url) => { re.lastIndex = 0; return re.test(url); } };
      } catch (e) { return null; }
    }
    p = p.toLowerCase();
    // keyword: *word*  (both stars, no dots/slashes inside fear -> treat as substring)
    if (p.startsWith('*') && p.endsWith('*') && p.length > 2 && !p.slice(1, -1).includes('/')) {
      const kw = p.slice(1, -1).replace(/^\*\.*/, '').replace(/\.*\*$/, '');
      if (!kw) return null;
      return { raw, test: (url) => url.toLowerCase().includes(kw) };
    }
    // strip scheme
    p = p.replace(/^https?:\/\//, '').replace(/^www\./, '');
    const hasPath = p.includes('/');
    let [hostPart, ...pathParts] = p.split('/');
    const pathPart = pathParts.join('/');
    hostPart = hostPart.replace(/^\*\./, '').replace(/:\d+$/, '');
    if (!hostPart && !pathPart) return null;
    const hostRe = hostPart
      ? '(^|\\.)' + hostPart.split('*').map(escapeReg).join('.*')
      : '';
    const pathRe = pathPart
      ? escapeReg(pathPart).replace(/\\\*/g, '.*')
      : null;
    const compiledHost = hostPart ? new RegExp(hostRe + '$', 'i') : null;
    const compiledPath = pathRe !== null ? new RegExp('^/' + pathRe, 'i') : null;
    return {
      raw,
      test: (urlStr) => {
        const n = normalizeUrl(urlStr);
        if (!n.ok) return false;
        if (compiledHost && !compiledHost.test(n.host)) return false;
        if (compiledPath && !compiledPath.test('/' + n.path.replace(/^\//, ''))) return false;
        if (!hostPart && pathRe !== null) return n.full.includes(pathPart.replace(/\*/g, ''));
        return true;
      }
    };
  }

  function compileList(patterns) {
    return (Array.isArray(patterns) ? patterns : []).map(compilePattern).filter(Boolean);
  }

  function matchesAny(urlStr, patterns) {
    if (!patterns || !patterns.length) return false;
    const compiled = compileList(patterns);
    for (const c of compiled) {
      try { if (c.test(urlStr)) return true; } catch (e) { /* ignore */ }
    }
    return false;
  }

  root.FocusLockMatcher = {
    normalizeUrl, isInternalUrl, domainOf, parseDomainInput,
    compilePattern, compileList, matchesAny
  };
})(typeof self !== 'undefined' ? self : globalThis);
