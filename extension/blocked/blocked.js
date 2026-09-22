/* Blocked page logic */
(function () {
  const q = new URLSearchParams(location.search);
  const url = q.get('url') || '';
  const list = q.get('list') || 'Blocked';
  const mode = q.get('mode') || '';
  document.getElementById('blockedUrl').textContent = url;
  document.getElementById('listPill').textContent =
    (mode === 'nuclear' ? '☢ Nuclear Block — ' : mode === 'whitelist' ? 'Allow-only — ' : '') + list;

  const dashboardUrl = chrome.runtime.getURL('options/options.html');
  const frogCard = document.getElementById('frogCard');
  const frogTitle = document.getElementById('frogTitle');
  const frogMeta = document.getElementById('frogMeta');
  const frogProgress = document.getElementById('frogProgress');
  const frogProgressLabel = document.getElementById('frogProgressLabel');
  const frogCta = document.getElementById('frogCta');

  function renderFrog(raw) {
    const state = raw && (raw.state || raw.frogStatus || raw);
    const frog = state && (state.frog || state.selected || state.task);
    if (!frog || !frog.title) {
      frogCard.hidden = false;
      frogTitle.textContent = 'Choose one important task first.';
      frogMeta.textContent = 'Your boundary stays protected while you decide what deserves your best attention.';
      frogProgress.hidden = true;
      frogProgressLabel.hidden = true;
      frogCta.hidden = false;
      frogCta.href = dashboardUrl + '#frog';
      return;
    }
    const tracked = Number(state.trackedSeconds ?? state.progressSeconds ?? 0);
    const required = Number(state.requiredSeconds ?? state.targetSeconds ?? 0);
    const percent = required > 0 ? Math.min(100, Math.round(tracked / required * 100)) : 0;
    const mins = Math.floor(tracked / 60);
    const targetMins = Math.ceil(required / 60);
    frogCard.hidden = false;
    frogTitle.textContent = frog.title;
    frogMeta.textContent = frog.projectName || frog.dueDate ? [frog.projectName, frog.dueDate].filter(Boolean).join(' · ') : 'Finish this before returning to the boundary app.';
    frogProgress.hidden = false;
    frogProgressLabel.hidden = false;
    frogProgress.querySelector('span').style.width = percent + '%';
    frogProgress.setAttribute('aria-valuenow', String(percent));
    frogProgressLabel.textContent = targetMins ? `${mins} of ${targetMins} min focused · ${percent}%` : `${percent}% complete`;
    frogCta.hidden = true;
  }

  chrome.runtime.sendMessage({ type: 'frogStatus' }, (response) => {
    if (chrome.runtime.lastError) return renderFrog(null);
    renderFrog(response);
  });

  document.getElementById('goBack').onclick = () => history.length > 1 ? history.back() : location.replace('chrome://newtab/');
  document.getElementById('dashboard').onclick = () => chrome.tabs.update({ url: dashboardUrl });
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
