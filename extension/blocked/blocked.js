/* Blocked page logic */
(function () {
  const q = new URLSearchParams(location.search);
  const url = q.get('url') || '';
  const list = q.get('list') || 'Blocked';
  const mode = q.get('mode') || '';
  document.getElementById('blockedUrl').textContent = url;
  document.getElementById('listPill').textContent =
    (mode === 'nuclear' ? '☢ Nuclear Block — ' : mode === 'whitelist' ? 'Allow-only — ' : '') + list;

  const QUOTES = [
    '“You can do anything, but not everything.” — David Allen',
    '“Focus is a matter of deciding what not to do.” — John Carmack',
    '“What you do every day matters more than what you do once in a while.”',
    '“Distraction is the enemy of depth.” — Cal Newport',
    '“Future-you is watching right now through memories.”'
  ];
  document.getElementById('quote').textContent = QUOTES[Math.floor(Math.random() * QUOTES.length)];

  document.getElementById('goBack').onclick = () => history.length > 1 ? history.back() : location.replace('chrome://newtab/');
  document.getElementById('dashboard').onclick = () => chrome.tabs.update({ url: chrome.runtime.getURL('options/options.html') });
  document.getElementById('closeTab').onclick = (e) => { e.preventDefault(); chrome.tabs.getCurrent(t => t && chrome.tabs.remove(t.id)); };

  // Emergency 5-min break with type + delay (disabled for frozen/nuclear-with-password)
  const PHRASE = 'i choose focus ' + Math.floor(100 + Math.random() * 900);
  document.getElementById('phrase').textContent = '“' + PHRASE + '”';
  const box = document.getElementById('snoozeBox');
  const input = document.getElementById('phraseInput');
  const go = document.getElementById('snoozeGo');
  const count = document.getElementById('count');
  document.getElementById('snoozeBtn').onclick = () => {
    box.style.display = box.style.display === 'block' ? 'none' : 'block';
  };
  let timer = null, left = 60;
  input.oninput = () => {
    if (input.value.trim().toLowerCase() === PHRASE && !timer) {
      timer = setInterval(() => {
        left -= 1;
        count.textContent = String(left);
        if (left <= 0) {
          clearInterval(timer);
          go.disabled = false;
          go.textContent = 'Unlock 5 minutes';
        }
      }, 1000);
    }
  };
  go.onclick = async () => {
    await chrome.runtime.sendMessage({ type: 'snooze', url, minutes: 5 });
    location.replace(url);
  };
})();
