// Fallback in-page guard: if the SW redirect misses (SPA navigations),
// ask for a verdict and hard-replace the page.
(() => {
  let inFlight = false;
  let watch = false;
  let timer;
  async function check() {
    if (inFlight) return;
    inFlight = true;
  try {
    const url = location.href;
    if (/^(chrome|chrome-extension|about):/i.test(url)) return;
    const v = await chrome.runtime.sendMessage({ type: 'verdict', url });
    watch = v?.watch === true;
    if (v && v.blocked) {
      const dest = chrome.runtime.getURL('blocked/blocked.html')
        + '?url=' + encodeURIComponent(url.slice(0, 800))
        + '&list=' + encodeURIComponent(v.listName || '')
        + '&mode=' + encodeURIComponent(v.mode || '');
      location.replace(dest);
      watch = false;
    }
  } catch (e) { /* extension context invalidated */ }
  finally {
    inFlight = false;
    if (watch && document.visibilityState === 'visible') timer = setTimeout(check, 2000);
  }
  }
  document.addEventListener('visibilitychange', () => {
    clearTimeout(timer);
    if (document.visibilityState === 'visible') void check();
  });
  void check();
})();
