/* Blocked page logic */
(function () {
  const q = new URLSearchParams(location.search);
  const url = q.get('url') || '';
  const list = q.get('list') || 'Blocked';
  const mode = q.get('mode') || '';
  const sharedNuke = mode === 'shared-nuke' || (mode === 'nuclear' && /shared nuclear/i.test(list));
  const permanent = mode === 'permanent' || (!mode && /permanent/i.test(list));
  const dailyCapBlock = ['group-limit', 'daily-limit', 'global-limit'].includes(mode);
  document.getElementById('blockedUrl').textContent = url;
  document.getElementById('listPill').textContent =
    permanent ? 'Permanent block'
      : sharedNuke ? '☢ Shared Nuclear Block' : (mode === 'nuclear' ? '☢ Nuclear Block — ' : mode === 'whitelist' ? 'Allow-only — ' : '') + list;
  if (permanent) {
    // "No way out": hide the frog task card, the dashboard link, and every
    // snooze control. Only close-tab/back navigation remains.
    document.querySelector('.sub').textContent = 'This site is permanently blocked in FocusLock. There is no removal, credits, or emergency pass.';
    document.getElementById('snoozeBtn').hidden = true;
    document.getElementById('snoozeBox').style.display = 'none';
    document.getElementById('frogCard').hidden = true;
    document.getElementById('dashboard').hidden = true;
  } else if (sharedNuke) {
    document.querySelector('.sub').textContent = 'This shared Nuclear block is active on every device. Use the complete reset in the FocusLock Android or Windows app to clear it.';
    document.getElementById('snoozeBtn').hidden = true;
    document.getElementById('snoozeBox').style.display = 'none';
  } else if (dailyCapBlock) {
    document.querySelector('.sub').textContent = 'This daily limit is active. A 5-minute pause cannot override it; earned Focus time permits access again when the limit allows.';
    document.getElementById('snoozeBtn').hidden = true;
    document.getElementById('snoozeBox').style.display = 'none';
  } else if (mode === 'nuclear') {
    document.getElementById('snoozeBtn').hidden = true;
    document.getElementById('snoozeBox').style.display = 'none';
  }

  const dashboardUrl = chrome.runtime.getURL('options/options.html');
  const frogCard = document.getElementById('frogCard');
  const frogTitle = document.getElementById('frogTitle');
  const frogMeta = document.getElementById('frogMeta');
  const frogProgress = document.getElementById('frogProgress');
  const frogProgressLabel = document.getElementById('frogProgressLabel');
  const frogCta = document.getElementById('frogCta');

  function renderFrog(raw) {
    if (raw?.supported === false) {
      frogCard.hidden = false;
      frogCard.setAttribute('aria-busy', 'false');
      frogTitle.textContent = 'Today’s Frog is on your phone.';
      frogMeta.textContent = 'The Android app keeps this task on-device, so Chrome cannot read its selection or progress yet.';
      frogProgress.hidden = true;
      frogProgressLabel.hidden = true;
      frogCta.hidden = true;
      return;
    }
    const state = raw && (raw.state || raw.frogStatus || raw);
    const frog = state && (state.frog || state.selected || state.task);
    if (!frog || !frog.title) {
      frogCard.hidden = false;
      frogCard.setAttribute('aria-busy', 'false');
      frogTitle.textContent = raw?.supported === true ? 'Choose one important task first.' : 'Focus task unavailable.';
      frogMeta.textContent = raw?.supported === true
        ? 'Your boundary stays protected while you decide what deserves your best attention.'
        : 'Your block is still active. Open the Focus dashboard to check your account and sync status.';
      frogProgress.hidden = true;
      frogProgressLabel.hidden = true;
      frogCta.hidden = raw?.supported === true;
      frogCta.href = dashboardUrl;
      frogCta.textContent = 'Open Focus dashboard →';
      return;
    }
    const tracked = Number(state.trackedSeconds ?? state.progressSeconds ?? 0);
    const required = Number(state.requiredSeconds ?? state.targetSeconds ?? 0);
    const percent = required > 0 ? Math.min(100, Math.round(tracked / required * 100)) : 0;
    const mins = Math.floor(tracked / 60);
    const targetMins = Math.ceil(required / 60);
    frogCard.hidden = false;
    frogCard.setAttribute('aria-busy', 'false');
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
    if (permanent) return; // no escape routes on a permanent block
    if (chrome.runtime.lastError) {
      frogCard.hidden = false;
      frogCard.setAttribute('aria-busy', 'false');
      frogTitle.textContent = 'Focus task unavailable.';
      frogMeta.textContent = 'Your block is still active. Open the dashboard to check your task.';
      frogProgress.hidden = true; frogProgressLabel.hidden = true;
      frogCta.hidden = false; frogCta.href = dashboardUrl;
      frogCta.textContent = 'Open Focus dashboard →';
      return;
    }
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
  const snoozeButton = document.getElementById('snoozeBtn');
  snoozeButton.setAttribute('aria-expanded', 'false');
  snoozeButton.onclick = () => {
    const open = box.style.display !== 'block';
    box.style.display = open ? 'block' : 'none';
    snoozeButton.setAttribute('aria-expanded', String(open));
    if (open) input.focus();
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
    } else if (input.value.trim().toLowerCase() !== PHRASE && timer) {
      clearInterval(timer); timer = null; left = 60; count.textContent = '60';
      go.disabled = true; go.textContent = 'Unlock 5 minutes';
    }
  };
  go.onclick = async () => {
    if (permanent || sharedNuke || mode === 'nuclear' || left > 0 || input.value.trim().toLowerCase() !== PHRASE) return;
    go.disabled = true;
    try {
      const result = await chrome.runtime.sendMessage({ type: 'snooze', url, minutes: 5 });
      if (result?.ok !== true) throw new Error(result?.error || 'This block cannot be paused right now.');
      location.replace(url);
    } catch (error) {
      go.disabled = false;
      let status = document.getElementById('snoozeStatus');
      if (!status) {
        status = document.createElement('p'); status.id = 'snoozeStatus'; status.className = 'hint';
        status.setAttribute('role', 'alert'); box.appendChild(status);
      }
      status.textContent = error?.message || 'Could not start the break. Your block remains active.';
    }
  };
})();
