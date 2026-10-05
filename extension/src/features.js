/* Pure commitment, Frog-cycle and durable focus-session rules. */
(function (root) {
  'use strict';
  const DAY = 86400000;
  const clamp = (value, min, max, fallback) => Number.isFinite(Number(value))
    ? Math.max(min, Math.min(max, Math.floor(Number(value)))) : fallback;
  const dateKey = date => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
  function frogState(raw, now = Date.now()) {
    raw = raw && typeof raw === 'object' ? raw : {};
    const wakeHour = clamp(raw.wakeHour ?? 5, 0, 23, 5);
    const date = new Date(now);
    if (date.getHours() < wakeHour) date.setDate(date.getDate() - 1);
    const cycle = dateKey(date);
    const rolled = !raw.cycleDate || raw.cycleDate < cycle;
    const result = {
      enabled: raw.enabled === true, wakeHour,
      requiredSeconds: clamp(raw.requiredSeconds ?? 1800, 60, 28800, 1800),
      cycleDate: rolled ? cycle : String(raw.cycleDate),
      armed: rolled ? false : raw.armed === true,
      frog: !rolled && raw.frog?.title ? { id: String(raw.frog.id || ''), title: String(raw.frog.title).slice(0, 200) } : null,
      trackedSeconds: rolled ? 0 : clamp(raw.trackedSeconds ?? 0, 0, 28800, 0),
      tickedOff: !rolled && raw.tickedOff === true,
    };
    if (result.enabled && new Date(now).getHours() >= wakeHour && result.cycleDate === dateKey(new Date(now))) result.armed = true;
    result.locked = result.enabled && result.armed && !(result.tickedOff && result.trackedSeconds >= result.requiredSeconds);
    result.phase = !result.enabled || !result.armed ? 'not-armed' : !result.frog ? 'pick-frog' : result.locked ? 'working' : 'complete';
    return result;
  }
  function strictEnd(state, requested, now = Date.now()) {
    const end = Number(requested);
    if (!Number.isFinite(end) || end <= now || end > now + 30 * DAY) throw new Error('Choose an end time within the next 30 days.');
    const active = state.strictMode === true && (!Number(state.strictEndsAt) || Number(state.strictEndsAt) > now);
    if (active && (!Number(state.strictEndsAt) || end <= Number(state.strictEndsAt))) throw new Error('An active commitment can only be extended.');
    return end;
  }
  function timerState(raw) {
    if (!raw || typeof raw !== 'object' || !raw.id || !Number.isFinite(raw.startedAt) || raw.startedAt <= 0) return null;
    return { id: String(raw.id), startedAt: raw.startedAt, targetMinutes: clamp(raw.targetMinutes, 1, 480, 25),
      accountId: typeof raw.accountId === 'string' ? raw.accountId : '',
      ratio: clamp(raw.ratio, 1, 20, 4), frogId: String(raw.frogId || ''), frogCycle: String(raw.frogCycle || ''),
      title: String(raw.title || 'Chrome focus').slice(0, 200) };
  }
  function elapsed(timer, now = Date.now()) {
    return timer ? Math.min(timer.targetMinutes * 60, Math.max(0, Math.floor((now - timer.startedAt) / 1000))) : 0;
  }
  function weeklyState(raw) {
    if (!raw || typeof raw !== 'object') return { enabled: false, days: [1, 2, 3, 4, 5], startMinute: 540, endMinute: 1020, lastWindow: '' };
    return { enabled: raw.enabled === true, days: [...new Set((Array.isArray(raw.days) ? raw.days : []).filter(day => Number.isInteger(day) && day >= 0 && day <= 6))],
      startMinute: clamp(raw.startMinute, 0, 1439, 540), endMinute: clamp(raw.endMinute, 0, 1439, 1020), lastWindow: String(raw.lastWindow || '') };
  }
  function weeklyWindow(raw, now = Date.now()) {
    const rule = weeklyState(raw);
    if (!rule.enabled || !rule.days.length) return null;
    // Construct local wall-clock boundaries to preserve weekday ownership and
    // local times across overnight windows and daylight-saving transitions.
    for (const offset of [0, -1]) {
      const start = new Date(now); start.setDate(start.getDate() + offset);
      if (!rule.days.includes(start.getDay())) continue;
      start.setHours(Math.floor(rule.startMinute / 60), rule.startMinute % 60, 0, 0);
      const end = new Date(start);
      if (rule.endMinute <= rule.startMinute) end.setDate(end.getDate() + 1);
      end.setHours(Math.floor(rule.endMinute / 60), rule.endMinute % 60, 0, 0);
      if (now >= start.getTime() && now < end.getTime()) return { key: String(start.getTime()), endsAt: end.getTime() };
    }
    return null;
  }
  root.FocusLockFeatures = { frogState, strictEnd, timerState, elapsed, weeklyState, weeklyWindow };
})(typeof self !== 'undefined' ? self : globalThis);
