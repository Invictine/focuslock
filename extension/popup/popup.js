/* Popup: current site, quick block, focus timers, frozen + nuclear */
(async function () {
  const M = self.FocusLockMatcher, S = self.FocusLockStore;
  const $ = (id) => document.getElementById(id);
  const status = (t) => { $('status').textContent = t; setTimeout(() => $('status').textContent = '', 3000); };

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const url = (tab && tab.url) || '';
  const domain = M.domainOf(url) || '(no page)';
  $('domain').textContent = domain;

  let state = await S.load();
  const key = S.todayKey();
  const secs = (state.stats[key] && state.stats[key][domain]) || 0;
  $('today').textContent = 'today: ' + Math.floor(secs / 60) + 'm ' + (secs % 60) + 's · blocked ' + (state.blockedTotal || 0) + '×';

  const pick = $('listPick');
  pick.innerHTML = state.lists.map(l => `<option value="${l.id}">${l.name} (${l.mode})</option>`).join('') || '<option value="">No lists</option>';

  $('openDash').onclick = () => chrome.tabs.create({ url: chrome.runtime.getURL('options/options.html') });

  $('allowSite').onclick = async () => {
    await chrome.runtime.sendMessage({ type: 'snooze', url, minutes: 5 });
    status('Allowed for 5 minutes. Make it count.');
  };

  $('blockSite').onclick = async () => {
    const listId = pick.value;
    await S.update(async (st) => {
      const list = st.lists.find(l => l.id === listId) || st.lists[0];
      if (!list) return st;
      if (list.lockedUntil > Date.now()) { status('List is frozen — cannot edit.'); return st; }
      const pat = domain;
      if (!list.sites.includes(pat)) list.sites.push(pat);
      list.enabled = true;
      return st;
    });
    await chrome.runtime.sendMessage({ type: 'refresh' });
    try { await chrome.tabs.reload(tab.id); } catch (e) {}
    status('Blocked ' + domain);
  };

  document.querySelectorAll('[data-min]').forEach(btn => {
    btn.onclick = async () => {
      const mins = Number(btn.dataset.min);
      const listId = pick.value;
      await S.update(async (st) => {
        st.schedules.push({
          id: S.uid('sch'), listId, type: 'timer',
          name: mins + '-min focus', startTs: Date.now(), endTs: Date.now() + mins * 60000
        });
        const l = st.lists.find(x => x.id === listId);
        if (l) l.enabled = true;
        return st;
      });
      await chrome.runtime.sendMessage({ type: 'refresh' });
      status('Locked for ' + mins + ' min. No escape. 🔒');
    };
  });

  $('frozenBtn').onclick = async () => {
    const listId = pick.value;
    if (!confirm('❄ Frozen Turkey: this list CANNOT be edited or disabled until the timer ends. Continue?')) return;
    const mins = Number(prompt('Freeze for how many minutes?', '60')) || 60;
    await S.update(async (st) => {
      const l = st.lists.find(x => x.id === listId);
      if (l) { l.enabled = true; l.lockedUntil = Date.now() + mins * 60000; }
      st.schedules.push({ id: S.uid('sch'), listId, type: 'frozen', name: 'Frozen ' + mins + 'm', startTs: Date.now(), endTs: Date.now() + mins * 60000, locked: true });
      return st;
    });
    await chrome.runtime.sendMessage({ type: 'refresh' });
    status('Frozen for ' + mins + ' min. ❄');
  };

  $('nuclearBtn').onclick = async () => {
    if (!confirm('☢ Nuclear: block EVERYTHING except your allow-list?')) return;
    const mins = Number(prompt('Nuclear for how many minutes?', '30')) || 30;
    await S.update(async (st) => {
      st.nuclear = { active: true, until: Date.now() + mins * 60000, allow: st.nuclear.allow || [] };
      return st;
    });
    await chrome.runtime.sendMessage({ type: 'refresh' });
    status('Nuclear active for ' + mins + ' min. ☢');
  };
})();
