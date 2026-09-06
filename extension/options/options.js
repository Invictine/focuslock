/* FocusLock dashboard */
(function () {
  const S = self.FocusLockStore;
  const $ = (id) => document.getElementById(id);
  const toast = (t) => { const el = $('toast'); el.textContent = t; el.style.display = 'block'; clearTimeout(el._h); el._h = setTimeout(() => el.style.display = 'none', 2600); };
  const esc = (s) => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  let state = null;
  const DAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  let rSel = [1, 2, 3, 4, 5];

  async function refresh() {
    state = await S.load();
    renderLists(); renderSched(); renderStats(); renderSettings();
    await chrome.runtime.sendMessage({ type: 'refresh' }).catch(() => {});
  }

  // ---- tabs ----
  document.querySelectorAll('nav button').forEach(b => {
    b.onclick = () => {
      document.querySelectorAll('nav button').forEach(x => x.classList.remove('on'));
      document.querySelectorAll('section.tab').forEach(x => x.classList.remove('on'));
      b.classList.add('on');
      $('tab-' + b.dataset.tab).classList.add('on');
    };
  });

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
    $('pwState').textContent = state.security.hash ? '🔒 Password is set.' : 'No password set.';
    $('nucAllow').value = (state.nuclear.allow || []).join('\n');
    $('idleSec').value = (state.settings && state.settings.idleTimeoutSec) || 60;
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

  refresh();
})();
