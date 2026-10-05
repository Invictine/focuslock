/* Edit account-shared rules while preserving rows owned by other devices. */
(function () {
  const box = document.getElementById('sharedPolicyPanel');
  if (!box) return;
  const $ = id => document.getElementById(id);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const send = message => chrome.runtime.sendMessage(message);
  let config = null, editingSchedule = null, permanentSites = [];
  box.innerHTML = `<details class="advanced"><summary>Account schedules and individual limits</summary>
    <div class="card"><h2>Shared website and app rules</h2><p class="d">These rules sync with Android. Chrome enforces website rules; native apps enforce application rules. Chrome-only list rules stay in Advanced.</p>
      <button id="sharedRulesLoad" class="ghost" type="button">Load account rules</button><p id="sharedRulesFeedback" class="mut" role="status" aria-live="polite"></p>
      <div id="sharedRulesForms" hidden>
        <details class="advanced"><summary>Individual daily limits</summary>
          <label for="sharedLimitTarget">Target</label><select id="sharedLimitTarget"></select>
          <label for="sharedLimitMinutes">Daily minutes (0 removes the daily cap)</label><input id="sharedLimitMinutes" type="number" min="0" max="1440" value="30">
          <div class="btnrow"><button id="sharedLimitSave" class="go" type="button">Save daily limit</button></div><div id="sharedLimitRows"></div>
        </details>
        <details class="advanced"><summary>Weekly blocking schedules</summary>
          <label for="sharedScheduleName">Schedule name</label><input id="sharedScheduleName" maxlength="80" placeholder="Work hours">
          <label for="sharedScheduleTarget">Target</label><select id="sharedScheduleTarget"></select>
          <div class="grid2"><div><label for="sharedScheduleStart">Start</label><input id="sharedScheduleStart" type="time" value="09:00"></div><div><label for="sharedScheduleEnd">End</label><input id="sharedScheduleEnd" type="time" value="17:00"></div></div>
          <fieldset style="border:0;padding:0"><legend>Days on which the block starts</legend><div class="days" id="sharedScheduleDays"></div></fieldset>
          <label><input id="sharedScheduleEnabled" type="checkbox" checked> Schedule enabled</label>
          <p class="mut">An end time earlier than the start runs overnight. Equal times block for the full selected day.</p>
          <div class="btnrow"><button id="sharedScheduleSave" class="go" type="button">Add schedule</button><button id="sharedScheduleNew" class="ghost" type="button">New schedule</button></div><div id="sharedScheduleRows"></div>
        </details>
      </div>
    </div></details>`;
  const names = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  $('sharedScheduleDays').innerHTML = names.map((name, i) => `<label><input type="checkbox" data-shared-day="${i}" ${i >= 1 && i <= 5 ? 'checked' : ''}> ${name}</label>`).join('');
  const feedback = message => { $('sharedRulesFeedback').textContent = message; };
  async function load() {
    $('sharedRulesLoad').disabled = true;
    try {
      const result = await send({ type: 'sharedRulesGet' });
      if (!result?.ok) throw new Error(result?.error || 'Account rules unavailable. Sign in and retry.');
      config = result.config;
      permanentSites = self.FocusLockStore.normalizePermanentSites((await self.FocusLockStore.load()).permanentSites);
      $('sharedRulesForms').hidden = false; render(); feedback('Account rules loaded.');
    } catch (e) { feedback(e.message); }
    finally { $('sharedRulesLoad').disabled = false; }
  }
  $('sharedRulesLoad').onclick = () => void load();
  function targets() {
    const permanent = new Set(permanentSites);
    return [...(config?.sites || []).filter(site => !permanent.has(site.domain)).map(site => ({ kind: 'website', key: site.domain, label: site.domain })),
      ...(config?.apps || []).map(app => ({ kind: 'app', key: app.packageName, label: app.appName || app.packageName }))];
  }
  function render() {
    const options = targets().map(target => `<option value="${esc(JSON.stringify([target.kind, target.key]))}">${esc(target.label)} · ${target.kind === 'app' ? 'application' : 'website'}</option>`).join('');
    const previousTarget = $('sharedLimitTarget').value;
    $('sharedLimitTarget').innerHTML = options; if (previousTarget) $('sharedLimitTarget').value = previousTarget;
    $('sharedScheduleTarget').innerHTML = `<option value='["all","*"]'>All selected targets</option>` + options;
    $('sharedLimitRows').innerHTML = (config?.limits || []).map((row, index) => `<div class="sched"><strong>${esc(row.label || row.targetKey)}</strong><span class="mut"> ${esc(row.targetKind)} · ${Number(row.dailyLimitMinutes) || 0} min/day${row.sessionLimitMinutes ? ` · ${Number(row.sessionLimitMinutes)} min/session` : ''}</span><button class="ghost" type="button" data-limit-edit="${index}">Edit daily limit</button></div>`).join('') || '<p class="mut">No individual daily limits.</p>';
    $('sharedScheduleRows').innerHTML = (config?.schedules || []).map((row, index) => `<div class="sched"><strong>${esc(row.label)}</strong><span class="mut"> ${esc(row.targetKey)} · ${(row.days || []).map(day => names[day]).join(', ')} · ${time(row.startMinute)}–${time(row.endMinute)} · ${row.isEnabled ? 'On' : 'Off'}</span><div class="btnrow"><button class="ghost" type="button" data-schedule-edit="${index}">Edit</button><button class="ghost" type="button" data-schedule-remove="${index}">Remove</button></div></div>`).join('') || '<p class="mut">No account schedules.</p>';
    box.querySelectorAll('[data-limit-edit]').forEach(button => button.onclick = () => {
      const row = config.limits[Number(button.dataset.limitEdit)];
      $('sharedLimitTarget').value = JSON.stringify([row.targetKind === 'site' ? 'website' : row.targetKind, row.targetKey]);
      if (!$('sharedLimitTarget').value) { feedback('This target is no longer in the editable shared list. Manage it on Android.'); return; }
      $('sharedLimitMinutes').value = row.dailyLimitMinutes || 0; $('sharedLimitMinutes').focus();
    });
    box.querySelectorAll('[data-schedule-edit]').forEach(button => button.onclick = () => edit(config.schedules[Number(button.dataset.scheduleEdit)]));
    box.querySelectorAll('[data-schedule-remove]').forEach(button => button.onclick = () => {
      const row = config.schedules[Number(button.dataset.scheduleRemove)];
      if (confirm(`Remove the shared schedule “${row.label}”?`)) void save('schedules', row, true);
    });
    lock();
  }
  function time(minute) { return `${String(Math.floor(minute / 60) % 24).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`; }
  const minute = value => { const [h, m] = value.split(':').map(Number); return h * 60 + m; };
  function edit(row) {
    editingSchedule = row || null;
    $('sharedScheduleName').value = row?.label || '';
    $('sharedScheduleTarget').value = JSON.stringify([row?.targetKind === 'site' ? 'website' : row?.targetKind || 'all', row?.targetKey || '*']);
    $('sharedScheduleStart').value = time(row?.startMinute ?? 540); $('sharedScheduleEnd').value = time(row?.endMinute ?? 1020);
    $('sharedScheduleEnabled').checked = row ? row.isEnabled : true;
    box.querySelectorAll('[data-shared-day]').forEach(input => { input.checked = (row?.days || [1, 2, 3, 4, 5]).includes(Number(input.dataset.sharedDay)); });
    $('sharedScheduleSave').textContent = row ? 'Save schedule' : 'Add schedule';
    if (row) $('sharedScheduleName').focus();
  }
  $('sharedScheduleNew').onclick = () => edit(null);
  $('sharedLimitSave').onclick = () => {
    if (!config || !$('sharedLimitTarget').value) return feedback('Load account rules and choose a target first.');
    const [targetKind, targetKey] = JSON.parse($('sharedLimitTarget').value);
    const minutes = Number($('sharedLimitMinutes').value);
    if (!Number.isInteger(minutes) || minutes < 0 || minutes > 1440) return feedback('Choose 0–1440 daily minutes.');
    const previous = (config.limits || []).find(row => (row.targetKind === 'site' ? 'website' : row.targetKind) === targetKind && row.targetKey === targetKey) || {};
    void save('limits', { ...previous, targetKind: previous.targetKind || targetKind, targetKey, dailyLimitMinutes: minutes });
  };
  $('sharedScheduleSave').onclick = () => {
    if (!config || !$('sharedScheduleTarget').value) return feedback('Load account rules first.');
    const [targetKind, targetKey] = JSON.parse($('sharedScheduleTarget').value);
    const days = [...box.querySelectorAll('[data-shared-day]:checked')].map(input => Number(input.dataset.sharedDay));
    const label = $('sharedScheduleName').value.trim(), start = minute($('sharedScheduleStart').value), end = minute($('sharedScheduleEnd').value);
    if (!label || !days.length || !Number.isFinite(start) || !Number.isFinite(end)) return feedback('Add a name, at least one day, and valid start and end times.');
    void save('schedules', { scheduleId: editingSchedule?.scheduleId || self.FocusLockStore.uid('schedule'), label, targetKind, targetKey,
      days, startMinute: start, endMinute: end, isEnabled: $('sharedScheduleEnabled').checked });
  };
  let saving = false;
  function lock() {
    const strict = config?.prefs?.strictMode && (!config.prefs.strictEndsAt || config.prefs.strictEndsAt > Date.now());
    box.querySelectorAll('#sharedRulesForms button, #sharedRulesForms input, #sharedRulesForms select').forEach(input => { input.disabled = saving || Boolean(strict); });
  }
  async function save(collection, row, remove = false) {
    if (saving) return;
    saving = true; lock(); feedback('Saving account rule…');
    try {
      const result = await send({ type: 'sharedRuleSave', collection, row, remove,
        version: config[collection === 'schedules' ? 'schedulesUpdatedAt' : 'limitsUpdatedAt'] || 0 });
      if (!result?.ok) throw new Error(result?.error || 'Could not save. Your existing rules remain active.');
      await load(); if (collection === 'schedules') edit(null); feedback('Rule saved to your account.');
    } catch (e) { feedback(e.message); }
    finally { saving = false; lock(); }
  }
  chrome.storage.onChanged?.addListener((changes, area) => {
    if (area !== 'local' || !changes[self.FocusLockStore.KEY] || !config) return;
    const state = changes[self.FocusLockStore.KEY].newValue;
    config.prefs = { ...config.prefs, strictMode: state?.strictMode, strictEndsAt: state?.strictEndsAt }; lock();
  });
})();
