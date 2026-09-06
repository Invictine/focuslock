// Fallback in-page guard: if the SW redirect misses (SPA navigations),
// ask for a verdict and hard-replace the page.
(async () => {
  try {
    const url = location.href;
    if (/^(chrome|chrome-extension|about):/i.test(url)) return;
    const v = await chrome.runtime.sendMessage({ type: 'verdict', url });
    if (v && v.blocked) {
      const dest = chrome.runtime.getURL('blocked/blocked.html')
        + '?url=' + encodeURIComponent(url.slice(0, 800))
        + '&list=' + encodeURIComponent(v.listName || '')
        + '&mode=' + encodeURIComponent(v.mode || '');
      location.replace(dest);
    }
  } catch (e) { /* extension context invalidated */ }
})();
