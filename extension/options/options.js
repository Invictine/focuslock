/* FocusLock dashboard */
(function () {
  const S = self.FocusLockStore;
  const M = self.FocusLockMatcher;
  const $ = (id) => document.getElementById(id);
  const toast = (t) => { const el = $('toast'); el.textContent = t; el.style.display = 'block'; clearTimeout(el._h); el._h = setTimeout(() => el.style.display = 'none', 2600); };
  const esc = (s) => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  let state = null;
  const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  let rSel = [1, 2, 3, 4, 5];

  // Focus credit settings — local cache mirrored to cross-platform userPrefs.
  // Field-level last-writer-wins uses the per-field *_UpdatedAt ms timestamps.
  const FOCUS_PREFS_KEY = 'focuslock.focus.prefs.v1';
  const FOCUS_DEFAULT_RATIO = 4;
  const FOCUS_DEFAULT_BONUS = 5;
  const FOCUS_RATIO_RANGE = [1, 20];
  const FOCUS_BONUS_RANGE = [0, 60];
  let dashboard = null;
  let focusPrefs = { ratio: FOCUS_DEFAULT_RATIO, bonus: FOCUS_DEFAULT_BONUS, ratioUpdatedAt: 0, bonusUpdatedAt: 0 };
  let focusError = '';
  let prefsPushTimer = null;
  const timer = { targetMin: 25, startTs: 0, handle: null };

  // Boundaries surfaces (applications + editable websites).
  let boundarySurface = 'sites';
  let siteSearch = '';
  let siteCategory = 'all';
  let searchHandle = null;

  async function refresh() {
    state = await S.load();
    focusPrefs = await loadFocusPrefs();
    renderLists(); renderSched(); renderStats(); renderSettings(); renderBoundaries();
    void refreshProtection();
    await chrome.runtime.sendMessage({ type: 'refresh' }).catch(() => {});
  }

  // ---- tabs ----
  function selectTab(tab) {
    const titles = { stats: 'Focus', sched: 'Focus', blocks: 'Boundaries', settings: 'Settings', account: 'Account' };
    if (!titles[tab]) tab = 'stats';
    document.querySelectorAll('section.tab').forEach(section => section.classList.toggle('on', section.id === 'tab-' + tab));
    document.querySelectorAll('button[data-tab]').forEach(button => {
      const selected = button.dataset.tab === tab || (button.closest('header') && button.dataset.tab === 'stats' && tab === 'sched');
      button.classList.toggle('on', Boolean(selected));
      if (selected) button.setAttribute('aria-current', 'page');
      else button.removeAttribute('aria-current');
    });
    $('pageTitle').textContent = titles[tab];
    $('focusViews').hidden = tab !== 'stats' && tab !== 'sched';
    if (tab === 'stats' || tab === 'sched' || tab === 'blocks') void refreshFocus();
    if (tab === 'settings' || tab === 'account') void refreshProtection();
  }
  document.querySelectorAll('button[data-tab]').forEach(button => {
    button.onclick = () => selectTab(button.dataset.tab);
  });
  const query = new URLSearchParams(location.search);
  selectTab(query.has('account') ? 'account' : query.get('tab') || 'stats');

  // ---- block lists ----
  function lockInfo(l) {
    if (l.lockedUntil > Date.now()) {
      const m = Math.ceil((l.lockedUntil - Date.now()) / 60000);
      return `<span class="badge lock">❄ frozen ${m}m</span>`;
    }
    return '';
  }

  function renderLists() {
    const box = $('lists');
    box.innerHTML = '';
    for (const l of state.lists) {
      const locked = l.lockedUntil > Date.now();
      const div = document.createElement('div');
      div.className = 'card';
      div.innerHTML = `
        <div class="listhead">
          <input data-f="name" value="${esc(l.name)}" style="font-weight:700;max-width:220px" ${locked ? 'disabled' : ''} />
          <span class="badge ${l.enabled ? 'on' : ''}">${l.enabled ? '● active' : '○ off'}</span>
          <span class="badge">${esc(l.mode)}</span>${lockInfo(l)}
          <span style="flex:1"></span>
          <button data-a="toggle">${l.enabled ? 'Disable' : 'Enable'}</button>
          <button data-a="del" class="red" ${locked ? 'disabled' : ''}>Delete</button>
        </div>
        <div class="grid2">
          <div><label>Sites / patterns (one per line)</label><textarea data-f="sites" ${locked ? 'disabled' : ''}>${esc(l.sites.join('\n'))}</textarea></div>
          <div><label>Exceptions — never block (one per line)</label><textarea data-f="exceptions" ${locked ? 'disabled' : ''}>${esc((l.exceptions || []).join('\n'))}</textarea></div>
        </div>
        <div class="grid2">
          <div><label>Mode</label><select data-f="mode" ${locked ? 'disabled' : ''}>
            <option value="blacklist" ${l.mode === 'blacklist' ? 'selected' : ''}>Blacklist — block these</option>
            <option value="whitelist" ${l.mode === 'whitelist' ? 'selected' : ''}>Allow-only — block everything else</option>
          </select></div>
          <div><label>Daily limit (minutes on these sites, 0 = off)</label><input data-f="dailyLimitMin" type="number" min="0" max="1440" value="${l.dailyLimitMin || 0}" ${locked ? 'disabled' : ''} /></div>
        </div>
        <label style="margin-top:10px"><input data-f="alwaysOn" type="checkbox" style="width:auto" ${l.alwaysOn ? 'checked' : ''} ${locked ? 'disabled' : ''} /> Always on when no schedule matches</label>
        <div class="btnrow"><button data-a="save" class="go" ${locked ? 'disabled' : ''}>Save list</button></div>`;
      div.querySelector('[data-a="toggle"]').onclick = async () => {
        if (locked) return toast('Frozen — cannot disable until timer ends.');
        if (l.enabled && state.security.hash) {
          const pw = prompt('Password to disable protection:');
          if (!await S.verifyPassword(state, pw || '')) return toast('Wrong password.');
        }
        await S.update(async (st) => { st.lists.find(x => x.id === l.id).enabled = !l.enabled; return st; });
        await refresh(); toast('Updated.');
      };
      div.querySelector('[data-a="del"]').onclick = async () => {
        if (!confirm('Delete "' + l.name + '"?')) return;
        await S.update(async (st) => { st.lists = st.lists.filter(x => x.id !== l.id); st.schedules = st.schedules.filter(s => s.listId !== l.id); return st; });
        await refresh();
      };
      div.querySelector('[data-a="save"]').onclick = async () => {
        const g = (f) => div.querySelector(`[data-f="${f}"]`);
        const sites = g('sites').value.split('\n').map(s => s.trim()).filter(Boolean);
        const exceptions = g('exceptions').value.split('\n').map(s => s.trim()).filter(Boolean);
        await S.update(async (st) => {
          const t = st.lists.find(x => x.id === l.id);
          t.name = g('name').value.trim() || t.name;
          t.sites = sites; t.exceptions = exceptions; t.mode = g('mode').value;
          t.dailyLimitMin = Math.max(0, Number(g('dailyLimitMin').value) || 0);
          t.alwaysOn = g('alwaysOn').checked;
          return st;
        });
        await refresh(); toast('List saved.');
      };
      box.appendChild(div);
    }
  }

  $('newList').onclick = async () => {
    await S.update(async (st) => {
      st.lists.push({ id: S.uid('list'), name: 'New block', mode: 'blacklist', enabled: true, alwaysOn: true, sites: ['example.com'], exceptions: [], lockedUntil: 0, dailyLimitMin: 0 });
      return st;
    });
    await refresh();
  };

  $('addPreset').onclick = async () => {
    const name = prompt('Preset? (social / video / news / shopping)', 'social') || 'social';
    const sites = S.PRESETS[name] || S.PRESETS.social;
    await S.update(async (st) => {
      st.lists.push({ id: S.uid('list'), name: 'Preset: ' + name, mode: 'blacklist', enabled: true, alwaysOn: true, sites: [...sites], exceptions: [], lockedUntil: 0, dailyLimitMin: 0 });
      return st;
    });
    await refresh(); toast('Preset added.');
  };

  $('exportBtn').onclick = async () => {
    const blob = new Blob([JSON.stringify(state, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = 'focuslock-backup.json'; a.click();
  };
  $('importBtn').onclick = () => $('importFile').click();
  $('importFile').onchange = async (e) => {
    const f = e.target.files[0]; if (!f) return;
    try {
      const data = JSON.parse(await f.text());
      if (!data.lists) throw new Error('bad file');
      await S.save(Object.assign(S.defaultState(), data));
      await refresh(); toast('Imported.');
    } catch (err) { toast('Import failed.'); }
  };

  // ---- schedules ----
  function fillListSelects() {
    for (const id of ['qList', 'pList', 'rList']) {
      $(id).innerHTML = state.lists.map(l => `<option value="${l.id}">${esc(l.name)}</option>`).join('');
    }
  }

  function renderSched() {
    fillListSelects();
    const box = $('schedList');
    if (!state.schedules.length) { box.innerHTML = '<p class="mut">No schedules yet. Start a timer above.</p>'; return; }
    const t = Date.now();
    box.innerHTML = '';
    for (const s of [...state.schedules].sort((a, b) => (a.endTs || 0) - (b.endTs || 0))) {
      const list = state.lists.find(l => l.id === s.listId);
      const active = s.type === 'recurring' ? true : (t >= s.startTs && t < s.endTs);
      const left = s.endTs ? Math.max(0, Math.ceil((s.endTs - t) / 60000)) + 'm left' : '';
      const frozen = s.type === 'frozen' || s.locked;
      const d = document.createElement('div');
      d.className = 'sched';
      d.innerHTML = `<b>${esc(s.name || s.type)}</b> → ${esc(list ? list.name : '?')}
        <span class="badge ${active ? 'on' : ''}">${active ? '● live' : '○ idle'}</span>
        ${frozen ? '<span class="badge lock">❄ locked</span>' : ''} <span class="mut">${esc(s.type)} ${esc(left)}</span>
        <div class="mut">${s.type === 'recurring' ? esc((s.days || []).map(x => DAYS[x]).join(', ')) + ' ' + esc(s.start || '') + '–' + esc(s.end || '') : new Date(s.startTs).toLocaleString() + ' → ' + new Date(s.endTs).toLocaleString()}</div>`;
      if (!frozen || s.endTs <= t) {
        const b = document.createElement('button'); b.textContent = 'Delete'; b.className = 'ghost'; b.style.marginTop = '8px';
        b.onclick = async () => { await S.update(async (st) => { st.schedules = st.schedules.filter(x => x.id !== s.id); return st; }); await refresh(); };
        d.appendChild(b);
      }
      box.appendChild(d);
    }
  }

  $('qStart').onclick = async () => {
    const mins = Math.max(1, Number($('qMin').value) || 25);
    await S.update(async (st) => {
      st.schedules.push({ id: S.uid('sch'), listId: $('qList').value, type: 'timer', name: mins + '-min focus', startTs: Date.now(), endTs: Date.now() + mins * 60000 });
      const l = st.lists.find(x => x.id === $('qList').value); if (l) l.enabled = true;
      return st;
    });
    await refresh(); toast('Timer started.');
  };
  $('qFrozen').onclick = async () => {
    if (!confirm('Frozen lock cannot be undone early. Continue?')) return;
    const mins = Math.max(1, Number($('qMin').value) || 25);
    await S.update(async (st) => {
      const l = st.lists.find(x => x.id === $('qList').value); if (l) { l.enabled = true; l.lockedUntil = Date.now() + mins * 60000; }
      st.schedules.push({ id: S.uid('sch'), listId: $('qList').value, type: 'frozen', name: 'Frozen ' + mins + 'm', startTs: Date.now(), endTs: Date.now() + mins * 60000, locked: true });
      return st;
    });
    await refresh(); toast('Frozen. No escape. ❄');
  };
  $('pStart').onclick = async () => {
    const f = Number($('pFocus').value) || 25, br = Number($('pBreak').value) || 5, r = Number($('pRounds').value) || 4;
    const total = (f + br) * r;
    await S.update(async (st) => {
      st.schedules.push({ id: S.uid('sch'), listId: $('pList').value, type: 'pomodoro', name: `Pomodoro ${f}/${br}×${r}`, startTs: Date.now(), endTs: Date.now() + total * 60000, focusMin: f, breakMin: br, rounds: r });
      const l = st.lists.find(x => x.id === $('pList').value); if (l) l.enabled = true;
      return st;
    });
    await refresh(); toast('Pomodoro started.');
  };

  // recurring day picker
  (function () {
    const box = $('rDays');
    DAYS.forEach((d, i) => {
      const b = document.createElement('button');
      b.textContent = d; if (rSel.includes(i)) b.classList.add('on');
      b.onclick = () => { rSel = rSel.includes(i) ? rSel.filter(x => x !== i) : [...rSel, i]; b.classList.toggle('on'); };
      box.appendChild(b);
    });
  })();
  $('rAdd').onclick = async () => {
    await S.update(async (st) => {
      st.schedules.push({ id: S.uid('sch'), listId: $('rList').value, type: 'recurring', name: $('rName').value || 'Schedule', days: [...rSel], start: $('rStart').value, end: $('rEnd').value });
      return st;
    });
    await refresh(); toast('Schedule added.');
  };

  // ---- stats ----
  function fmt(s) { s = Math.round(s); const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60); return h ? h + 'h ' + m + 'm' : m + 'm ' + (s % 60) + 's'; }
  function renderStats() {
    const key = S.todayKey();
    const day = state.stats[key] || {};
    const entries = Object.entries(day).sort((a, b) => b[1] - a[1]);
    const total = entries.reduce((a, [, s]) => a + s, 0);
    $('statSum').textContent = entries.length ? `${fmt(total)} tracked today across ${entries.length} sites.` : 'No usage tracked yet today. Browse a little (productively).';
    const max = entries.length ? entries[0][1] : 1;
    $('statTable').innerHTML = entries.slice(0, 30).map(([d, s]) =>
      `<table><tr><td style="width:32%">${esc(d)}</td><td><div class="bar"><i style="width:${Math.round(s / max * 100)}%"></i></div></td><td style="width:90px">${fmt(s)}</td></tr></table>`).join('');
    $('blockSum').textContent = `Lifetime blocks: ${state.blockedTotal || 0}`;
    $('blockLog').innerHTML = (state.blockedLog || []).slice(0, 20).map(e =>
      `<div class="sched">🚫 <b>${esc(e.domain)}</b> <span class="mut">${new Date(e.ts).toLocaleTimeString()} · ${esc(e.listName || '')}</span></div>`).join('') || '<p class="mut">Nothing blocked yet. Suspiciously productive…</p>';
  }
  $('clearStats').onclick = async () => {
    if (!confirm('Clear all usage stats?')) return;
    await S.update(async (st) => { st.stats = {}; st.blockedLog = []; st.blockedTotal = 0; return st; });
    await refresh();
  };

  // ---- settings ----
  function renderSettings() {
    const ratio = effectiveRatio();
    const bonus = effectiveBonus();
    if ($('ratioRange')) {
      $('ratioRange').value = String(ratio);
      $('ratioValue').textContent = String(ratio);
      if ($('ratioValueOut')) $('ratioValueOut').textContent = ratio + ' : 1';
    }
    if ($('bonusRange')) {
      $('bonusRange').value = String(bonus);
      $('bonusValue').textContent = String(bonus);
      if ($('bonusValueOut')) $('bonusValueOut').textContent = '+' + bonus + ' min';
    }
    // refreshFocus() may call this before Store.load() resolves — state-independent
    // slider sync above still applies.
    if (!state) return;
    $('pwState').textContent = state.security.hash ? '🔒 Password is set.' : 'No password set.';
    $('nucAllow').value = (state.nuclear.allow || []).join('\n');
    $('idleSec').value = (state.settings && state.settings.idleTimeoutSec) || 60;
    renderLockdown();
    renderTickTick();
  }
  $('pwSet').onclick = async () => {
    if ($('pw1').value !== $('pw2').value) return toast('Passwords do not match.');
    if ($('pw1').value.length < 4) return toast('Use at least 4 characters.');
    await S.update(async (st) => { await S.setPassword(st, $('pw1').value); return st; });
    $('pw1').value = $('pw2').value = '';
    await refresh(); toast('Password set.');
  };
  $('pwClear').onclick = async () => {
    const pw = prompt('Current password to remove lock:') || '';
    if (!await S.verifyPassword(state, pw)) return toast('Wrong password.');
    await S.update(async (st) => { st.security.salt = ''; st.security.hash = ''; return st; });
    await refresh();
  };
  $('nucSave').onclick = async () => {
    const allow = $('nucAllow').value.split('\n').map(s => s.trim()).filter(Boolean);
    await S.update(async (st) => { st.nuclear.allow = allow; return st; });
    await refresh(); toast('Allow-list saved.');
  };
  $('nucStart').onclick = async () => {
    await S.update(async (st) => { st.nuclear = { active: true, until: Date.now() + 30 * 60000, allow: st.nuclear.allow || [] }; return st; });
    await refresh(); toast('☢ Nuclear active 30m.');
  };
  $('nucStop').onclick = async () => {
    if (state.security.hash) {
      const pw = prompt('Password to stop Nuclear early:');
      if (!await S.verifyPassword(state, pw || '')) return toast('Wrong password.');
    } else if (!confirm('Stop Nuclear early?')) return;
    await S.update(async (st) => { st.nuclear.active = false; return st; });
    await refresh();
  };
  $('saveTrack').onclick = async () => {
    const v = Math.min(600, Math.max(15, Number($('idleSec').value) || 60));
    await S.update(async (st) => { st.settings.idleTimeoutSec = v; return st; });
    await refresh(); toast('Saved.');
  };
  $('resetAll').onclick = async () => {
    if (!confirm('Reset ALL FocusLock data?')) return;
    await S.save(S.defaultState());
    await refresh();
  };

  // ---- lockdown, protection checklist, sync & notifications ----
  let protection = null;
  let notifyLevel = 'unknown';

  function relTime(ts) {
    if (!ts) return 'never';
    const s = Math.max(0, Math.floor((Date.now() - ts) / 1000));
    if (s < 60) return 'just now';
    if (s < 3600) return Math.floor(s / 60) + 'm ago';
    if (s < 86400) return Math.floor(s / 3600) + 'h ago';
    return Math.floor(s / 86400) + 'd ago';
  }
  function fmtCountdown(ms) {
    const total = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(total / 3600), m = Math.floor((total % 3600) / 60), s = total % 60;
    return (h ? h + 'h ' : '') + m + 'm ' + String(s).padStart(2, '0') + 's';
  }

  function renderLockdown() {
    const badge = $('lockdownBadge'); if (!badge) return;
    if (!state) { badge.textContent = '…'; return; }
    const t = Date.now();
    const frozen = state.lists.filter(l => l.lockedUntil > t);
    const nuclear = state.nuclear && state.nuclear.active && state.nuclear.until > t;
    const enabled = state.lists.filter(l => l.enabled).length;
    badge.classList.toggle('lock', Boolean(frozen.length || nuclear));
    if (frozen.length) {
      const until = Math.max.apply(null, frozen.map(l => l.lockedUntil));
      badge.textContent = 'Frozen';
      $('lockdownRemaining').textContent = 'Frozen · ' + fmtCountdown(until - t);
      $('lockdownDetail').textContent = frozen.length + ' of ' + state.lists.length + ' lists frozen. Cannot be undone early.';
    } else if (nuclear) {
      badge.textContent = 'Nuclear';
      $('lockdownRemaining').textContent = 'Nuclear · ' + fmtCountdown(state.nuclear.until - t);
      $('lockdownDetail').textContent = 'Everything is blocked except the allow-list.';
    } else {
      badge.textContent = enabled ? enabled + ' active' : 'Off';
      $('lockdownRemaining').textContent = 'No active lockdown';
      $('lockdownDetail').textContent = enabled ? enabled + ' of ' + state.lists.length + ' lists enabled.' : 'Every list is editable.';
    }
    $('lockdownLists').innerHTML = state.lists.map(l => {
      const locked = l.lockedUntil > t;
      const status = locked ? '❄ ' + fmtCountdown(l.lockedUntil - t) : (l.enabled ? '● active' : '○ off');
      return '<span class="badge ' + (l.enabled ? 'on' : '') + (locked ? ' lock' : '') + '">' + esc(l.name) + ' · ' + esc(status) + '</span>';
    }).join('');
  }

  $('lockdownStart').onclick = async () => {
    if (!state) return;
    const mins = Math.max(1, Math.min(1440, Number($('lockdownMin').value) || 30));
    if (!confirm('Lock every list for ' + mins + ' minutes? Frozen locks cannot be undone early.')) return;
    await S.update(async (st) => {
      const now = Date.now(), until = now + mins * 60000;
      for (const l of st.lists) {
        if (l.lockedUntil > now) continue;
        l.enabled = true; l.lockedUntil = until;
        st.schedules.push({ id: S.uid('sch'), listId: l.id, type: 'frozen', name: 'Lockdown ' + mins + 'm', startTs: now, endTs: until, locked: true });
      }
      return st;
    });
    await refresh();
    toast('Boundaries locked for ' + mins + ' minutes.');
  };

  function renderTickTick() {
    const el = $('tickStatus'); if (!el) return;
    el.textContent = 'Task bonus +' + effectiveBonus() + ' min applies to work you log here and to tasks completed in Android.';
  }
  $('openTickTick').onclick = () => chrome.tabs.create({ url: 'https://ticktick.com/webapp/' });

  function renderProtection() {
    const box = $('protectionList'); if (!box) return;
    const p = protection;
    if (!p) { box.innerHTML = '<p class="mut">Background tracker is not responding.</p>'; return; }
    const urlLabel = p.currentUrl ? (M.domainOf(p.currentUrl) || 'Chrome page') : 'Chrome page';
    const items = [
      { label: 'Tracking active', ok: p.engineRunning && p.focused, detail: p.focused ? 'Chrome focused · ' + urlLabel : 'Chrome not focused', state: p.engineRunning ? 'Running' : 'Offline' },
      { label: 'Idle gating', ok: p.idleTimeoutSec > 0 && p.idleState === 'active', detail: 'Pauses after ' + p.idleTimeoutSec + 's idle · now ' + p.idleState, state: p.idleState === 'active' ? 'Active' : 'Idle' },
      { label: 'Boundaries active', ok: p.listsActive > 0 || p.nuclearActive, detail: p.listsActive + ' of ' + p.listsTotal + ' lists active' + (p.nuclearActive ? ' · Nuclear on' : ''), state: p.listsActive > 0 ? 'Enforcing' : 'Idle' },
      { label: 'Account sync', ok: p.signedIn, detail: p.signedIn ? (p.lastSyncAt ? 'Last sync ' + relTime(p.lastSyncAt) : 'Awaiting first sync') : 'Sign in to sync across devices', state: p.signedIn ? 'On' : 'Off' },
    ];
    box.innerHTML = items.map(it =>
      '<div class="check-row"><span class="check-dot ' + (it.ok ? '' : 'off') + '"></span><div><strong>' + esc(it.label) + '</strong><span class="mut">' + esc(it.detail) + '</span></div><span class="check-state">' + esc(it.state) + '</span></div>').join('');
  }

  function renderSyncStatus() {
    const box = $('syncList'); if (!box) return;
    const p = protection;
    const signedIn = Boolean(p && p.signedIn);
    const rows = [
      { label: 'Account', value: signedIn ? 'Signed in' : 'Signed out', ok: signedIn },
      { label: 'Auto-sync', value: signedIn ? 'Every minute in background' : 'Off until signed in', ok: signedIn },
      { label: 'Last sync', value: (p && p.lastSyncAt) ? relTime(p.lastSyncAt) : 'Not yet', ok: Boolean(p && p.lastSyncAt) },
      { label: 'Notifications', value: notifyLevel === 'granted' ? 'Enabled' : String(notifyLevel || 'unknown'), ok: notifyLevel === 'granted' },
    ];
    box.innerHTML = rows.map(r =>
      '<div class="check-row"><span class="check-dot ' + (r.ok ? '' : 'off') + '"></span><div><strong>' + esc(r.label) + '</strong></div><span class="check-state">' + esc(r.value) + '</span></div>').join('');
  }

  async function refreshProtection() {
    try {
      protection = await chrome.runtime.sendMessage({ type: 'protectionStatus' });
    } catch (e) { protection = null; }
    notifyLevel = await new Promise(res => {
      try { chrome.notifications.getPermissionLevel(level => res(level || 'unknown')); }
      catch (e) { res('unknown'); }
    });
    renderProtection();
    renderSyncStatus();
  }

  setInterval(renderLockdown, 1000);
  setInterval(() => { if (!document.hidden) void refreshProtection(); }, 30000);

  // ---- Focus overview: cloud dashboard, rings, graph, work log, timer ----
  function focusDefaults() {
    return { ratio: FOCUS_DEFAULT_RATIO, bonus: FOCUS_DEFAULT_BONUS, ratioUpdatedAt: 0, bonusUpdatedAt: 0 };
  }
  function numOr(value, fallback) {
    if (value === null || value === undefined || value === '') return fallback;
    const n = Number(value);
    return Number.isFinite(n) ? n : fallback;
  }
  function clampRatio(value) {
    return Math.min(FOCUS_RATIO_RANGE[1], Math.max(FOCUS_RATIO_RANGE[0], Math.round(numOr(value, FOCUS_DEFAULT_RATIO))));
  }
  function clampBonus(value) {
    return Math.min(FOCUS_BONUS_RANGE[1], Math.max(FOCUS_BONUS_RANGE[0], Math.round(numOr(value, FOCUS_DEFAULT_BONUS))));
  }
  function effectiveRatio() { return clampRatio(focusPrefs.ratio); }
  function effectiveBonus() { return clampBonus(focusPrefs.bonus); }

  // Mirrors Android CreditBankRepository.calculateEarnedMinutes: floor(min/ratio) + bonus, min 1.
  function earnedLeisure(workMinutes, workRatio, taskBonusMinutes) {
    const minutes = Math.max(0, Math.floor(Number(workMinutes) || 0));
    const ratio = Number(workRatio) || 0;
    const bonus = Math.max(0, Math.floor(Number(taskBonusMinutes) || 0));
    const fromTime = ratio > 0 ? Math.floor(minutes / ratio) : minutes;
    const fromBonus = minutes > 0 ? bonus : 0;
    return Math.max(1, fromTime + fromBonus);
  }

  async function loadFocusPrefs() {
    try {
      const got = await chrome.storage.local.get(FOCUS_PREFS_KEY);
      const saved = got && got[FOCUS_PREFS_KEY] ? got[FOCUS_PREFS_KEY] : {};
      return {
        ratio: saved.ratio != null ? clampRatio(saved.ratio) : FOCUS_DEFAULT_RATIO,
        bonus: saved.bonus != null ? clampBonus(saved.bonus) : FOCUS_DEFAULT_BONUS,
        ratioUpdatedAt: numOr(saved.ratioUpdatedAt, 0),
        bonusUpdatedAt: numOr(saved.bonusUpdatedAt, 0),
      };
    } catch (e) { return focusDefaults(); }
  }

  async function saveFocusPrefsCache(prefs) {
    try { await chrome.storage.local.set({ [FOCUS_PREFS_KEY]: prefs }); } catch (e) { /* cache best-effort */ }
  }

  // Per-field last-writer-wins against the remote userPrefs doc. Remote wins only when
  // its field timestamp is strictly newer; otherwise the local value is pushed.
  async function syncFocusPrefs(remotePrefs) {
    const local = await loadFocusPrefs();
    const next = { ...local };
    const push = {};
    const remoteRatioAt = remotePrefs ? numOr(remotePrefs.workRatioUpdatedAt, 0) : 0;
    const remoteBonusAt = remotePrefs ? numOr(remotePrefs.taskBonusMinutesUpdatedAt, 0) : 0;

    if (remotePrefs && remotePrefs.workRatio != null && remoteRatioAt > numOr(local.ratioUpdatedAt, 0)) {
      next.ratio = clampRatio(remotePrefs.workRatio);
      next.ratioUpdatedAt = remoteRatioAt;
    } else if (numOr(local.ratioUpdatedAt, 0) > remoteRatioAt || (!remotePrefs && numOr(local.ratioUpdatedAt, 0) > 0)) {
      push.workRatio = local.ratio;
      push.workRatioUpdatedAt = local.ratioUpdatedAt;
    }

    if (remotePrefs && remotePrefs.taskBonusMinutes != null && remoteBonusAt > numOr(local.bonusUpdatedAt, 0)) {
      next.bonus = clampBonus(remotePrefs.taskBonusMinutes);
      next.bonusUpdatedAt = remoteBonusAt;
    } else if (numOr(local.bonusUpdatedAt, 0) > remoteBonusAt || (!remotePrefs && numOr(local.bonusUpdatedAt, 0) > 0)) {
      push.taskBonusMinutes = local.bonus;
      push.taskBonusMinutesUpdatedAt = local.bonusUpdatedAt;
    }

    focusPrefs = next;
    await saveFocusPrefsCache(next);
    if (Object.keys(push).length) void pushFocusPrefs(push);
    return next;
  }

  function setPrefsSyncState(message) {
    const el = $('prefsSyncState');
    if (el) el.textContent = message;
  }

  function prefsFieldsSnapshot() {
    const payload = {};
    if (numOr(focusPrefs.ratioUpdatedAt, 0) > 0) {
      payload.workRatio = effectiveRatio();
      payload.workRatioUpdatedAt = focusPrefs.ratioUpdatedAt;
    }
    if (numOr(focusPrefs.bonusUpdatedAt, 0) > 0) {
      payload.taskBonusMinutes = effectiveBonus();
      payload.taskBonusMinutesUpdatedAt = focusPrefs.bonusUpdatedAt;
    }
    return payload;
  }

  async function pushFocusPrefs(fields) {
    const payload = { ...(fields || prefsFieldsSnapshot()), updatedAt: Date.now() };
    if (payload.workRatio === undefined && payload.taskBonusMinutes === undefined) return;
    try {
      const res = await chrome.runtime.sendMessage({ type: 'savePrefs', prefs: payload });
      if (res && res.ok) setPrefsSyncState('Synced across devices.');
      else if (res && res.signedIn === false) setPrefsSyncState('Saved on this device · sign in to sync.');
      else setPrefsSyncState('Saved on this device.');
    } catch (e) { setPrefsSyncState('Saved on this device.'); }
  }

  function schedulePrefsPush() {
    if (prefsPushTimer) clearTimeout(prefsPushTimer);
    prefsPushTimer = setTimeout(() => { prefsPushTimer = null; void pushFocusPrefs(); }, 450);
  }

  function updateFocusSetting(field, value) {
    const now = Date.now();
    if (field === 'ratio') {
      focusPrefs.ratio = clampRatio(value);
      focusPrefs.ratioUpdatedAt = now;
    } else if (field === 'bonus') {
      focusPrefs.bonus = clampBonus(value);
      focusPrefs.bonusUpdatedAt = now;
    }
    void saveFocusPrefsCache(focusPrefs);
    setPrefsSyncState('Saving…');
    renderSettings();
    renderFocus();
    schedulePrefsPush();
  }

  if ($('ratioRange')) $('ratioRange').addEventListener('input', (e) => updateFocusSetting('ratio', e.target.value));
  if ($('bonusRange')) $('bonusRange').addEventListener('input', (e) => updateFocusSetting('bonus', e.target.value));

  function fmtClock(totalSeconds) {
    const s = Math.max(0, Math.round(totalSeconds));
    return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0');
  }
  function fmtMinSec(seconds) {
    const total = Math.max(0, Math.floor(seconds));
    const m = Math.floor(total / 60), s = total % 60;
    return m ? m + 'm ' + s + 's' : s + 's';
  }
  function setRing(id, pct) {
    const el = $(id); if (!el) return;
    const len = 2 * Math.PI * 52;
    el.style.strokeDasharray = String(len);
    el.style.strokeDashoffset = String(len * (1 - Math.min(1, Math.max(0, pct))));
  }

  async function refreshFocus() {
    focusPrefs = await loadFocusPrefs();
    let remotePrefs = null;
    try {
      const res = await chrome.runtime.sendMessage({ type: 'getDashboard' });
      if (res && res.signedIn && res.dashboard) {
        dashboard = res.dashboard; focusError = '';
        remotePrefs = res.dashboard.prefs || null;
      } else if (res && res.signedIn) { dashboard = null; focusError = ''; }
      else { dashboard = null; focusError = 'signed-out'; }
    } catch (e) {
      dashboard = null; focusError = (e && e.message) ? e.message : 'unavailable';
    }
    if (remotePrefs || focusError === '') {
      focusPrefs = await syncFocusPrefs(remotePrefs);
    }
    renderFocus();
    renderSettings();
    renderBoundaries();
  }

  function renderFocus() {
    const st = dashboard && dashboard.state ? dashboard.state : null;
    const sessions = dashboard && Array.isArray(dashboard.sessions) ? dashboard.sessions : [];
    const records = dashboard && Array.isArray(dashboard.records) ? dashboard.records : [];
    const ratio = effectiveRatio();

    const workMin = st ? Number(st.totalWorkSecondsToday || 0) / 60 : 0;
    const taskCount = st ? Math.max(0, Math.round(Number(st.tasksCompletedToday || 0))) : 0;
    const balanceSec = st ? Number(st.creditBalanceSeconds || 0) : 0;
    const scrollSec = st ? Number(st.totalScrollSecondsToday || 0) : 0;
    const focusPct = (workMin / 120) * 100;
    const taskPct = (taskCount / 7) * 100;

    $('focusRatioBadge').textContent = 'Work : scroll 1:' + ratio;
    setRing('focusRing', focusPct);
    setRing('taskRing', taskPct);
    $('focusPct').textContent = Math.round(Math.min(100, focusPct)) + '%';
    $('focusGoal').textContent = Math.round(workMin) + ' / 120 min today';
    $('taskCount').textContent = String(taskCount);
    $('taskGoal').textContent = taskCount + ' / 7 done';

    if (!dashboard) {
      $('balanceCaption').textContent = focusError === 'signed-out'
        ? 'Sign in to sync your Focus balance.'
        : 'Focus sync is unavailable right now.';
      $('leisureDetail').textContent = 'Real balance appears after your first sync.';
      $('scrollBankCaption').textContent = focusError === 'signed-out'
        ? 'Sign in to see your scroll bank.'
        : 'Scroll bank unavailable.';
    } else {
      $('balanceCaption').textContent = fmtMinSec(balanceSec) + ' of leisure banked at 1:' + ratio + '.';
      $('leisureDetail').textContent = st
        ? 'Worked ' + Math.round(workMin) + ' min · scrolled ' + Math.round(scrollSec / 60) + ' min today.'
        : 'No focus state yet today.';
      $('scrollBankCaption').textContent = balanceSec > 0
        ? fmtMinSec(balanceSec) + ' left in the bank.'
        : 'Empty bank — log work to earn leisure.';
    }
    const bankGoal = Math.max(effectiveRatio() * 60, balanceSec, 1);
    $('scrollBankBar').style.width = Math.max(0, Math.min(100, balanceSec / bankGoal * 100)) + '%';

    renderHourGraph(sessions);
    renderRecentWork(records);
    renderWorkPreview();
  }

  function renderHourGraph(sessions) {
    const hours = new Array(24).fill(0);
    const today = S.todayKey();
    for (const s of sessions) {
      const ts = Number(s.timestamp) || 0;
      if (!ts || S.todayKey(new Date(ts)) !== today) continue;
      const h = new Date(ts).getHours();
      hours[h] += Math.max(0, Number(s.durationMinutes) || 0);
    }
    const max = Math.max.apply(null, hours.concat([1]));
    $('hourGraph').innerHTML = hours.map((m, h) => {
      const pct = m ? Math.max(3, m / max * 100) : 0;
      return '<div class="hour-col" title="' + String(h).padStart(2, '0') + ':00 — ' + Math.round(m) + ' min"><i style="height:' + pct.toFixed(1) + '%"></i></div>';
    }).join('');
    $('hourAxis').innerHTML = [0, 6, 12, 18, 23].map(h => '<span>' + String(h).padStart(2, '0') + '</span>').join('');
  }

  function renderRecentWork(records) {
    const box = $('recentWork');
    if (!records.length) {
      $('recentSum').textContent = 'Nothing logged yet.';
      box.innerHTML = '<p class="mut">No work logged yet. Add your first block above.</p>';
      return;
    }
    const totalEarned = records.reduce((a, r) => a + Math.max(0, Number(r.earnedMinutesCredited) || 0), 0);
    $('recentSum').textContent = records.length + ' entries · ' + Math.round(totalEarned) + ' min leisure earned.';
    box.innerHTML = records.slice(0, 12).map(r => {
      const mins = Math.round(Number(r.durationMinutes) || 0);
      const earned = Math.round(Number(r.earnedMinutesCredited) || 0);
      const when = r.timestamp ? new Date(Number(r.timestamp)).toLocaleString() : '';
      return '<div class="sched"><b>' + esc(r.title) + '</b> <span class="mut">' + mins + ' min → +' + earned + ' min leisure' + (when ? ' · ' + esc(when) : '') + '</span></div>';
    }).join('');
  }

  function renderWorkPreview() {
    const el = $('workPreview'); if (!el) return;
    const minutes = Math.floor(Number($('workMinutes').value) || 0);
    if (!minutes) { el.textContent = ''; return; }
    const earned = earnedLeisure(minutes, effectiveRatio(), effectiveBonus());
    el.textContent = '≈ +' + earned + ' min leisure';
  }

  $('workTitle').addEventListener('input', renderWorkPreview);
  $('workMinutes').addEventListener('input', renderWorkPreview);
  $('workLog').onclick = async () => {
    const title = $('workTitle').value.trim();
    const minutes = Math.floor(Number($('workMinutes').value) || 0);
    if (!title) return toast('Add a short title for the work.');
    if (minutes < 1 || minutes > 480) return toast('Minutes must be between 1 and 480.');
    const earned = earnedLeisure(minutes, effectiveRatio(), effectiveBonus());
    $('workLog').disabled = true;
    try {
      const res = await chrome.runtime.sendMessage({
        type: 'addWorkRecord',
        record: { title, durationMinutes: minutes, timestamp: Date.now(), source: 'chrome-extension', earnedMinutesCredited: earned },
      });
      if (res && res.ok) {
        $('workTitle').value = '';
        await refreshFocus();
        toast('Logged ' + minutes + ' min · +' + earned + ' min leisure.');
      } else {
        toast(res && res.signedIn === false ? 'Sign in to log work.' : 'Could not log work.');
      }
    } catch (e) { toast('Could not log work.'); }
    finally { $('workLog').disabled = false; }
  };

  function resetTimerUI(stateText) {
    $('timerClock').textContent = fmtClock(timer.targetMin * 60);
    $('timerState').textContent = stateText || 'Ready';
  }
  function timerElapsedSec() { return timer.startTs ? Math.floor((Date.now() - timer.startTs) / 1000) : 0; }
  function stopTimerHandle() { if (timer.handle) { clearInterval(timer.handle); timer.handle = null; } }
  function tickTimer() {
    const remaining = timer.targetMin * 60 - timerElapsedSec();
    $('timerClock').textContent = fmtClock(Math.max(0, remaining));
    $('timerState').textContent = timerElapsedSec() < 300 ? 'Credit starts at 5 min…' : 'In focus';
    if (remaining <= 0) void finishTimer(true);
  }
  function setTimerPreset(min) {
    if (timer.startTs) { toast('Finish the running timer first.'); return; }
    timer.targetMin = min;
    document.querySelectorAll('#timerPresets button').forEach(b => b.classList.toggle('on', Number(b.dataset.min) === min));
    resetTimerUI('Ready');
  }
  function startTimer() {
    if (timer.startTs) return;
    timer.startTs = Date.now();
    stopTimerHandle();
    timer.handle = setInterval(tickTimer, 1000);
    $('timerStart').disabled = true;
    $('timerFinish').disabled = false;
    tickTimer();
  }
  async function finishTimer(completed) {
    if (!timer.startTs) return;
    const elapsedMin = Math.floor(timerElapsedSec() / 60);
    const target = timer.targetMin;
    stopTimerHandle();
    timer.startTs = 0;
    $('timerStart').disabled = false;
    $('timerFinish').disabled = true;
    if (!completed && elapsedMin < 5) {
      resetTimerUI('Ready');
      toast('Sessions under 5 minutes do not earn credit.');
      return;
    }
    const minutes = completed ? target : Math.min(elapsedMin, target);
    const earned = earnedLeisure(minutes, effectiveRatio(), 0);
    try {
      const res = await chrome.runtime.sendMessage({
        type: 'logFocusSession',
        session: { title: 'Chrome focus ' + target + 'm', durationMinutes: minutes, timestamp: Date.now(), source: 'chrome-extension', earnedMinutesCredited: earned },
      });
      if (res && res.ok) toast('Focus session logged: ' + minutes + ' min · +' + earned + ' min leisure.');
      else toast(res && res.signedIn === false ? 'Sign in to save focus sessions.' : 'Could not log the focus session.');
    } catch (e) { toast('Could not log the focus session.'); }
    resetTimerUI('Ready');
    await refreshFocus();
  }
  document.querySelectorAll('#timerPresets button').forEach(b => { b.onclick = () => setTimerPreset(Number(b.dataset.min)); });
  $('timerStart').onclick = startTimer;
  $('timerFinish').onclick = () => finishTimer(false);
  resetTimerUI('Ready');

  // ---- Boundaries: applications (synced, read-only) + editable websites ----
  function syncedApps() { return dashboard && Array.isArray(dashboard.apps) ? dashboard.apps : []; }
  function syncedSites() { return dashboard && Array.isArray(dashboard.sites) ? dashboard.sites : []; }

  function setSurface(surface) {
    boundarySurface = surface === 'apps' ? 'apps' : 'sites';
    document.querySelectorAll('#boundaryTabs button').forEach(b => {
      const on = b.dataset.surface === boundarySurface;
      b.classList.toggle('on', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    if ($('surface-apps')) $('surface-apps').hidden = boundarySurface !== 'apps';
    if ($('surface-sites')) $('surface-sites').hidden = boundarySurface !== 'sites';
  }

  function categoryFor(site, list) {
    const cleaned = String(site).replace(/^https?:\/\//, '').replace(/^\*\./, '');
    const host = (M.domainOf('https://' + cleaned + '/') || cleaned.split('/')[0]).toLowerCase();
    const hit = syncedSites().find(s => s.domain && s.domain.toLowerCase() === host);
    return (hit && hit.category) ? hit.category : (list ? list.name : 'Custom');
  }

  function allSiteRows() {
    const rows = [];
    if (!state) return rows;
    for (const l of state.lists) {
      const locked = l.lockedUntil > Date.now();
      for (const site of l.sites) {
        rows.push({ site, listId: l.id, listName: l.name, enabled: Boolean(l.enabled) && !locked, category: categoryFor(site, l) });
      }
    }
    return rows.sort((a, b) => a.site.localeCompare(b.site));
  }

  function siteCategoryList(rows) {
    const set = new Set();
    for (const r of rows) if (r.category) set.add(r.category);
    return [...set].sort((a, b) => a.localeCompare(b));
  }

  function renderBoundaries() {
    if (!$('appCount')) return;
    const apps = syncedApps();
    const rows = allSiteRows();
    $('appCount').textContent = String(apps.filter(a => a.isBlocked).length);
    $('siteCount').textContent = String(rows.length);
    renderApps(apps);
    renderSiteChips(rows);
    renderSiteRows(rows);
    const listSelect = $('addSiteList');
    if (listSelect && state) listSelect.innerHTML = state.lists.map(l => '<option value="' + esc(l.id) + '">' + esc(l.name) + '</option>').join('');
  }

  function renderApps(apps) {
    const box = $('appList'); if (!box) return;
    if (!dashboard) { box.innerHTML = '<p class="mut">Sign in to sync the shared application list.</p>'; return; }
    if (!apps.length) { box.innerHTML = '<p class="mut">No applications synced yet. Block apps in the Windows or Android app and they appear here.</p>'; return; }
    box.innerHTML = apps.map(a => {
      const on = Boolean(a.isBlocked);
      return '<div class="app-row"><div><strong>' + esc(a.appName || a.packageName) + '</strong>'
        + '<span class="mut">' + esc(a.category || 'App') + ' · ' + esc(a.packageName) + '</span></div>'
        + '<span class="badge ' + (on ? 'on' : '') + '">' + (on ? '● blocked' : '○ allowed') + '</span></div>';
    }).join('');
  }

  function renderSiteChips(rows) {
    const box = $('siteChips'); if (!box) return;
    const options = [['all', 'All'], ['blocked', 'Blocked']].concat(siteCategoryList(rows).map(c => [c, c]));
    if (!options.some(o => o[0] === siteCategory)) siteCategory = 'all';
    box.innerHTML = options.map(([value, label]) =>
      '<button type="button" class="chip ' + (siteCategory === value ? 'on' : '') + '" data-chip="' + esc(value) + '">' + esc(label) + '</button>').join('');
  }

  function filteredSiteRows(rows) {
    const q = (siteSearch || '').trim().toLowerCase();
    return rows.filter(r => {
      if (q && !r.site.toLowerCase().includes(q) && !r.listName.toLowerCase().includes(q)) return false;
      if (siteCategory === 'blocked') return r.enabled;
      if (siteCategory !== 'all' && r.category !== siteCategory) return false;
      return true;
    });
  }

  function renderSiteRows(rows) {
    const box = $('siteRows'); if (!box) return;
    if (!state) { box.innerHTML = '<p class="mut">Loading your websites…</p>'; return; }
    if (!rows.length) { box.innerHTML = '<p class="mut">No websites blocked yet. Add one or use a preset.</p>'; return; }
    const list = filteredSiteRows(rows);
    if (!list.length) { box.innerHTML = '<p class="mut">No websites match this search or filter.</p>'; return; }
    box.innerHTML = list.map(r => {
      const custom = !syncedSites().some(s => s.domain && s.domain.toLowerCase() === r.site.replace(/^\*\./, '').split('/')[0]);
      return '<div class="site-row"><div><strong>' + esc(r.site) + '</strong><span class="mut">'
        + esc(r.listName) + ' · ' + esc(r.category) + (custom ? ' · custom' : '') + '</span></div>'
        + '<span class="badge ' + (r.enabled ? 'on' : '') + '">' + (r.enabled ? '● blocked' : '○ off') + '</span>'
        + '<div class="btnrow" style="margin:0"><button type="button" class="red" data-del="' + esc(r.listId) + '|' + esc(r.site) + '">Delete</button></div></div>';
    }).join('');
  }

  document.querySelectorAll('#boundaryTabs button').forEach(b => { b.onclick = () => setSurface(b.dataset.surface); });
  setSurface(boundarySurface);

  $('siteSearch').addEventListener('input', (e) => {
    clearTimeout(searchHandle);
    searchHandle = setTimeout(() => {
      siteSearch = e.target.value || '';
      renderSiteRows(allSiteRows());
    }, 250);
  });
  $('siteChips').addEventListener('click', (e) => {
    const chip = e.target.closest('[data-chip]'); if (!chip) return;
    siteCategory = chip.dataset.chip;
    renderSiteChips(allSiteRows());
    renderSiteRows(allSiteRows());
  });
  $('siteRows').addEventListener('click', async (e) => {
    const btn = e.target.closest('[data-del]'); if (!btn) return;
    const parts = btn.dataset.del.split('|');
    const listId = parts[0], site = parts.slice(1).join('|');
    if (!confirm('Delete "' + site + '" from this list?')) return;
    await S.update(async (st) => { const l = st.lists.find(x => x.id === listId); if (l) l.sites = l.sites.filter(s => s !== site); return st; });
    await refresh();
    toast('Website removed.');
  });

  // Add Website dialog
  function openAddSite() {
    if (!state || !state.lists.length) return toast('Create a block list first.');
    $('addSiteError').hidden = true;
    $('addSiteInput').value = '';
    $('addSiteModal').hidden = false;
    $('addSiteInput').focus();
  }
  function closeAddSite() { $('addSiteModal').hidden = true; }
  function validateSite(raw) {
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
  $('addSite').onclick = openAddSite;
  $('addSiteCancel').onclick = closeAddSite;
  $('addSiteModal').addEventListener('click', (e) => { if (e.target === $('addSiteModal')) closeAddSite(); });
  $('addSiteInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); $('addSiteConfirm').click(); } });
  $('addSiteConfirm').onclick = async () => {
    const err = $('addSiteError');
    const parsed = validateSite($('addSiteInput').value);
    if (parsed.error) { err.textContent = parsed.error; err.hidden = false; return; }
    const listId = $('addSiteList').value;
    let outcome = 'added';
    await S.update(async (st) => {
      const l = st.lists.find(x => x.id === listId); if (!l) { outcome = 'missing'; return st; }
      if (l.lockedUntil > Date.now()) { outcome = 'locked'; return st; }
      if (l.sites.includes(parsed.value)) { outcome = 'duplicate'; return st; }
      l.sites.push(parsed.value); l.enabled = true;
      return st;
    });
    if (outcome === 'locked') { err.textContent = 'That list is frozen and cannot be edited.'; err.hidden = false; return; }
    if (outcome === 'missing') { err.textContent = 'Choose a block list.'; err.hidden = false; return; }
    await refresh();
    closeAddSite();
    toast(outcome === 'duplicate' ? 'Website already blocked.' : 'Added ' + parsed.value + '.');
  };

  // Presets mapped onto the existing list/preset model.
  async function applyPreset(kind) {
    if (!state) return;
    if (kind === 'unblock') {
      if (state.security && state.security.hash) {
        const pw = prompt('Password to disable protection:');
        if (!(await S.verifyPassword(state, pw || ''))) return toast('Wrong password.');
      }
      await S.update(async (st) => {
        for (const l of st.lists) if (!(l.lockedUntil > Date.now())) l.enabled = false;
        return st;
      });
      await refresh(); toast('All lists unblocked.');
      return;
    }
    const sites = kind === 'social' ? S.PRESETS.social : S.PRESETS.video;
    const name = kind === 'social' ? 'Social Media' : 'Video & Streaming';
    const stableId = kind === 'social' ? 'list_social' : 'list_video';
    await S.update(async (st) => {
      let list = st.lists.find(l => l.id === stableId) || st.lists.find(l => l.name.toLowerCase() === name.toLowerCase());
      if (!list) {
        list = { id: stableId, name, mode: 'blacklist', enabled: true, alwaysOn: true, sites: [], exceptions: [], lockedUntil: 0, dailyLimitMin: 0 };
        st.lists.push(list);
      }
      if (list.lockedUntil > Date.now()) return st;
      for (const s of sites) if (!list.sites.includes(s)) list.sites.push(s);
      list.enabled = true; list.alwaysOn = true;
      return st;
    });
    await refresh();
    toast(kind === 'social' ? 'Social sites blocked.' : 'Video sites blocked.');
  }
  $('presetSocial').onclick = () => applyPreset('social');
  $('presetVideo').onclick = () => applyPreset('video');
  $('presetUnblock').onclick = () => applyPreset('unblock');

  refresh();
  refreshFocus();
})();
