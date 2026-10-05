/* Android-aligned browser controls. All commitments and timers run in the worker. */
(function () {
  'use strict';
  const $ = id => document.getElementById(id);
  const send = message => chrome.runtime.sendMessage(message);
  let status = null, busy = false;
  const announce = text => { const el = $('featureFeedback'); if (el) el.textContent = text; };
  const strictPanel = $('strictPanel'), frogPanel = $('frogPanel');
  if (!strictPanel || !frogPanel) return;
  strictPanel.innerHTML = `
    <section class="card"><div class="listhead"><h2>Strict Mode</h2><span class="badge" id="strictBadge">Off</span></div>
      <p class="d" id="strictDescription">Commit to keeping your boundary rules unchanged until the end time. Existing rules keep enforcing as configured.</p>
      <p id="strictUntil" class="mut"></p>
      <div id="strictActivation">
        <div class="days" role="group" aria-label="Commitment duration"><button type="button" data-strict-choice="hours" class="on" aria-pressed="true">Hours</button><button type="button" data-strict-choice="days" aria-pressed="false">Days</button><button type="button" data-strict-choice="date" aria-pressed="false">End date</button></div>
        <div data-strict-input="hours"><label for="strictHours">Hours (1–720)</label><input id="strictHours" type="number" min="1" max="720" value="2"></div>
        <div data-strict-input="days" hidden><label for="strictDays">Days (1–30)</label><input id="strictDays" type="number" min="1" max="30" value="1"></div>
        <div data-strict-input="date" hidden><label for="strictDate">End at your local date and time</label><input id="strictDate" type="datetime-local"></div>
        <p class="mut">Boundary rules can only be changed after the commitment ends or with the configured approval. A signed-in commitment syncs to your account.</p>
        <div class="btnrow"><button id="strictCommit" class="go" type="button">Start commitment</button></div>
      </div>
      <p id="strictSyncState" class="mut" role="status"></p>
      <button id="featureRetry" class="ghost" type="button" hidden>Retry account sync</button>
    </section>
    <section class="card"><h2>Trusted person</h2><p class="d">Configure an email before starting. During a synced commitment, request approval to release that exact commitment.</p>
      <label for="guardianEmail">Email address</label><input id="guardianEmail" type="email" autocomplete="email" placeholder="name@example.com">
      <div class="btnrow"><button id="guardianLoad" class="ghost" type="button">Check saved email</button><button id="guardianSave" class="ghost" type="button">Save email</button><button id="guardianRequest" class="ghost" type="button" hidden>Request approval</button></div>
      <p id="guardianStatus" class="mut" role="status">Approval needs an online account and the configured email service.</p>
    </section>
    <section class="card"><h2>Weekly activation</h2><p class="d">Start Strict Mode during a weekly window on this computer. An active window commits until its end; a longer existing commitment stays in place.</p>
      <details class="advanced"><summary>Set a weekly window</summary>
        <fieldset style="border:0;padding:0"><legend>Days on which the window starts</legend><div class="days" id="strictWeeklyDays"></div></fieldset>
        <div class="grid2"><div><label for="strictWeeklyStart">Start</label><input id="strictWeeklyStart" type="time" value="09:00"></div><div><label for="strictWeeklyEnd">End</label><input id="strictWeeklyEnd" type="time" value="17:00"></div></div>
        <label><input id="strictWeeklyEnabled" type="checkbox"> Enable weekly activation</label>
        <p class="mut">Overnight windows belong to their start day. Equal times create a 24-hour window. Activation is checked when Chrome wakes and every minute while running.</p>
        <div class="btnrow"><button id="strictWeeklySave" class="ghost" type="button">Save weekly window</button></div>
      </details><p id="strictWeeklyStatus" class="mut" role="status"></p>
      <p class="mut">Weekly setup is Chrome-local; signed-in activations sync the resulting commitment. Phone place rules and home-only blocking remain on Android.</p>
    </section>
    <p id="featureFeedback" role="status" aria-live="polite"></p>`;
  frogPanel.innerHTML = `
    <section class="card" aria-labelledby="browserFrogHeading"><div class="listhead"><h2 id="browserFrogHeading">Eat the Frog</h2><span class="badge" id="browserFrogBadge">Chrome only</span></div>
      <p class="d" id="browserFrogDescription">Do your important task before opening your boundary websites. This browser’s task is separate from your phone’s Frog.</p>
      <div id="browserFrogSetup"><label for="browserFrogMinutes">Required focus minutes</label><input id="browserFrogMinutes" type="number" min="1" max="480" value="30">
        <label for="browserFrogWake">Day starts at (local hour, 0–23)</label><input id="browserFrogWake" type="number" min="0" max="23" value="5">
        <div class="btnrow"><button id="browserFrogEnable" class="ghost" type="button">Enable Eat the Frog</button></div></div>
      <div id="browserFrogTask" hidden><strong id="browserFrogTitle"></strong>
        <p class="mut" id="browserFrogProgressText"></p><progress id="browserFrogProgress" max="100" value="0" aria-label="Frog focus progress" style="width:100%"></progress>
        <div id="browserFrogPicker"><label for="browserFrogInput">Today’s important task</label><input id="browserFrogInput" maxlength="200" placeholder="What matters most today?">
          <div class="btnrow"><button id="browserFrogSelect" class="ghost" type="button">Choose task</button></div></div>
        <div class="btnrow"><button id="browserFrogFocus" class="go" type="button">Focus on this task</button><button id="browserFrogTick" class="ghost" type="button">Mark task done</button><button id="browserFrogChange" class="ghost" type="button">Change task</button></div>
      </div><p id="browserFrogFeedback" class="mut" role="status" aria-live="polite"></p>
    </section>`;
  let choice = 'hours';
  $('strictWeeklyDays').innerHTML = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map((name, day) => `<label><input type="checkbox" data-strict-day="${day}" ${day > 0 && day < 6 ? 'checked' : ''}> ${name}</label>`).join('');
  document.querySelectorAll('[data-strict-choice]').forEach(button => button.onclick = () => {
    choice = button.dataset.strictChoice;
    document.querySelectorAll('[data-strict-choice]').forEach(b => { b.classList.toggle('on', b === button); b.setAttribute('aria-pressed', String(b === button)); });
    document.querySelectorAll('[data-strict-input]').forEach(input => { input.hidden = input.dataset.strictInput !== choice; });
  });
  async function action(button, message, success) {
    if (busy) return;
    busy = true; button.disabled = true;
    try {
      const result = await send(message);
      if (!result?.ok) throw new Error(result?.error || 'Could not save. Try again when the background tracker is available.');
      if (success) success(result);
      await refresh();
    } catch (error) { announce(error.message); $('browserFrogFeedback').textContent = error.message; }
    finally { busy = false; button.disabled = false; render(); }
  }
  $('strictCommit').onclick = () => {
    const now = Date.now();
    const base = status?.strictMode ? Math.max(now, Number(status.strictEndsAt) || now) : now;
    const number = Number(choice === 'days' ? $('strictDays').value : $('strictHours').value);
    const end = choice === 'date' ? new Date($('strictDate').value).getTime() : base + number * (choice === 'days' ? 86400000 : 3600000);
    try { self.FocusLockFeatures.strictEnd({ strictMode: status?.strictMode, strictEndsAt: status?.strictEndsAt }, end, now); }
    catch (error) { announce(error.message); return; }
    if (choice !== 'date' && (!Number.isInteger(number) || number < 1)) { announce('Choose a positive whole number.'); return; }
    if (!confirm(`Commit until ${new Date(end).toLocaleString()}? You can extend this time, but cannot shorten it yourself.`)) return;
    void action($('strictCommit'), { type: 'strictCommit', endsAt: end, preset: choice }, result => {
      announce(result.localOnly ? 'Commitment active on Chrome only.' : result.synced ? 'Commitment active and synced.' : 'Commitment active on Chrome. Account sync is pending.');
    });
  };
  $('guardianLoad').onclick = () => void action($('guardianLoad'), { type: 'guardianGet' }, result => {
    $('guardianEmail').value = result.result?.email || '';
    $('guardianStatus').textContent = result.result?.email ? 'Saved trusted person loaded.' : 'No trusted person configured.';
  });
  $('featureRetry').onclick = () => void action($('featureRetry'), { type: 'featureRetry' }, result => { announce(result.strictPending || result.pendingSessions ? 'Account upload is still waiting. Sign into the originating account and retry.' : 'Saved changes synced.'); });
  $('strictWeeklySave').onclick = () => {
    const asMinute = value => { const [hour, minute] = value.split(':').map(Number); return hour * 60 + minute; };
    const rule = { enabled: $('strictWeeklyEnabled').checked, days: [...document.querySelectorAll('[data-strict-day]:checked')].map(input => Number(input.dataset.strictDay)), startMinute: asMinute($('strictWeeklyStart').value), endMinute: asMinute($('strictWeeklyEnd').value) };
    if (!Number.isFinite(rule.startMinute) || !Number.isFinite(rule.endMinute) || (rule.enabled && !rule.days.length)) { announce('Choose valid times and at least one weekday.'); return; }
    if (rule.enabled && !confirm('Save this weekly Strict window? If you are inside its hours now, your boundaries will be committed immediately until the window ends.')) return;
    void action($('strictWeeklySave'), { type: 'strictWeeklySave', rule }, () => { announce('Weekly window saved on Chrome.'); });
  };
  $('guardianSave').onclick = () => {
    if (!$('guardianEmail').checkValidity() || !$('guardianEmail').value.trim()) { $('guardianEmail').reportValidity(); announce('Enter a valid email address.'); return; }
    void action($('guardianSave'), { type: 'guardianSave', email: $('guardianEmail').value }, () => { $('guardianStatus').textContent = 'Trusted person saved to your account.'; });
  };
  $('guardianRequest').onclick = () => {
    if (!confirm('Send an approval request to your saved trusted person?')) return;
    void action($('guardianRequest'), { type: 'guardianRequest', sessionId: status?.strictSessionId, endsAt: status?.strictEndsAt }, () => { $('guardianStatus').textContent = 'Approval requested. Your commitment stays active until approved; the request expires in 30 minutes.'; });
  };
  $('browserFrogEnable').onclick = () => {
    const enabled = !status?.frog?.enabled;
    if (enabled && !confirm('Enable Eat the Frog on Chrome? At your wake hour, boundary websites stay blocked until your task is done and the required focus time is tracked.')) return;
    void action($('browserFrogEnable'), { type: 'frogConfigure', enabled, requiredMinutes: Number($('browserFrogMinutes').value), wakeHour: Number($('browserFrogWake').value) }, () => { $('browserFrogFeedback').textContent = enabled ? 'Eat the Frog enabled on this browser.' : 'Eat the Frog disabled.'; });
  };
  $('browserFrogSelect').onclick = () => void action($('browserFrogSelect'), { type: 'frogSelect', title: $('browserFrogInput').value }, () => { $('browserFrogInput').value = ''; $('browserFrogFeedback').textContent = 'Task selected. Track focus time and mark it done to release the Frog block.'; });
  $('browserFrogTick').onclick = () => void action($('browserFrogTick'), { type: 'frogTick', tickedOff: !status?.frog?.tickedOff });
  $('browserFrogChange').onclick = () => { $('browserFrogPicker').hidden = false; $('browserFrogInput').focus(); };
  $('browserFrogFocus').onclick = () => void action($('browserFrogFocus'), { type: 'focusTimerStart', minutes: Math.max(1, Math.ceil(Math.max(0, status.frog.requiredSeconds - status.frog.trackedSeconds) / 60)), ratio: Number($('ratioRange')?.value) || 4 }, () => { $('browserFrogFeedback').textContent = 'Focus session running. You can close this dashboard.'; });
  function render() {
    if (!status) return;
    const active = status.strictMode;
    $('strictBadge').textContent = active ? 'Committed' : 'Off';
    $('strictUntil').textContent = active ? (status.strictEndsAt ? `Committed until ${new Date(status.strictEndsAt).toLocaleString()}.` : 'An existing open-ended commitment is active.') : '';
    $('strictActivation').hidden = active && !status.strictEndsAt;
    $('strictCommit').textContent = active ? 'Extend commitment' : 'Start commitment';
    $('strictSyncState').textContent = status.strictPending ? 'Active on Chrome · sync waiting for the account that started this commitment.'
      : active && !status.strictOriginAccountId ? 'This commitment is Chrome-local. Sign in before starting your next commitment to share it.'
      : 'Offline enforcement uses saved commitments. Signed-in changes sync to the originating account.';
    $('featureRetry').hidden = !status.strictPending && !status.pendingSessions;
    $('guardianSave').disabled = busy || active;
    $('guardianEmail').disabled = active;
    $('guardianRequest').hidden = !active;
    $('guardianRequest').disabled = busy || !status.strictSessionId || status.strictPending || !status.strictOriginAccountId || status.strictOriginAccountId !== status.accountId;
    document.querySelectorAll('#strictWeeklyDays input, #strictWeeklyStart, #strictWeeklyEnd, #strictWeeklyEnabled, #strictWeeklySave').forEach(input => { input.disabled = busy; });
    $('strictWeeklyStatus').textContent = status.strictWeekly?.enabled ? 'Weekly activation enabled on this computer.' : 'No automatic weekly activation.';
    if (!document.querySelector('#strictPanel details[open]') && status.strictWeekly) {
      const rule = status.strictWeekly;
      const asTime = value => `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`;
      $('strictWeeklyStart').value = asTime(rule.startMinute); $('strictWeeklyEnd').value = asTime(rule.endMinute);
      $('strictWeeklyEnabled').checked = rule.enabled;
      document.querySelectorAll('[data-strict-day]').forEach(input => { input.checked = rule.days.includes(Number(input.dataset.strictDay)); });
    }
    const frog = status.frog;
    $('browserFrogSetup').hidden = frog.enabled && frog.locked;
    $('browserFrogEnable').textContent = frog.enabled ? 'Disable Eat the Frog' : 'Enable Eat the Frog';
    $('browserFrogTask').hidden = !frog.enabled;
    $('browserFrogBadge').textContent = frog.phase === 'complete' ? 'Complete' : frog.locked ? 'Boundaries protected' : 'Chrome only';
    $('browserFrogTitle').textContent = frog.frog?.title || (frog.armed ? 'Choose today’s important task.' : `Ready from ${frog.wakeHour}:00.`);
    $('browserFrogPicker').hidden = Boolean(frog.frog) || !frog.armed || frog.phase === 'complete';
    $('browserFrogProgress').value = Math.min(100, frog.trackedSeconds / frog.requiredSeconds * 100);
    $('browserFrogProgressText').textContent = `${Math.floor(frog.trackedSeconds / 60)} of ${Math.ceil(frog.requiredSeconds / 60)} min focused${frog.tickedOff ? ' · task marked done' : ' · mark the task done when finished'}`;
    $('browserFrogFocus').hidden = !frog.frog || !frog.locked;
    $('browserFrogFocus').disabled = busy || Boolean(status.timer);
    $('browserFrogTick').hidden = !frog.frog || frog.phase === 'complete';
    $('browserFrogTick').textContent = frog.tickedOff ? 'Task still needs work' : 'Mark task done';
    $('browserFrogChange').hidden = !frog.frog || !frog.locked;
    $('browserFrogChange').disabled = Boolean(status.timer);
    if (!frog.locked && document.activeElement !== $('browserFrogMinutes') && document.activeElement !== $('browserFrogWake')) {
      $('browserFrogMinutes').value = Math.ceil((frog.requiredSeconds || 1800) / 60); $('browserFrogWake').value = frog.wakeHour ?? 5;
    }
  }
  async function refresh() {
    try {
      const result = await send({ type: 'featureStatus' });
      if (!result?.frog) return;
      status = result; render();
    } catch (error) { announce('Background tracker unavailable. Reload this page to retry.'); }
  }
  chrome.storage.onChanged?.addListener((changes, area) => { if (area === 'local' && changes[self.FocusLockStore.KEY]) void refresh(); });
  document.addEventListener('visibilitychange', () => { if (!document.hidden) void refresh(); });
  setInterval(() => { if (!document.hidden) void refresh(); }, 15000);
  void refresh();
})();
