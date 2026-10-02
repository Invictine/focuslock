/* Shared Android account-policy verdicts for the extension service worker. */
(function (root) {
  'use strict';

  const matcher = () => root.FocusLockMatcher || globalThis.FocusLockMatcher;
  const record = value => value && typeof value === 'object' && !Array.isArray(value);
  const finite = value => Number.isFinite(Number(value)) ? Number(value) : 0;
  const positiveSeconds = value => Math.max(0, finite(value));

  function dayKey(t) {
    const d = new Date(t);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  }

  function normalizedHost(value) {
    const raw = String(value || '').trim().toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
    if (!raw) return '';
    try {
      const url = new URL(raw.includes('://') ? raw : `https://${raw}`);
      return url.hostname.toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
    } catch (_) { return raw.split('/')[0].split(':')[0]; }
  }

  function hostMatches(host, key) {
    host = normalizedHost(host);
    key = normalizedHost(key);
    return Boolean(host && key && (host === key || host.endsWith(`.${key}`)));
  }

  function getHost(url) {
    try { return new URL(url).hostname.toLowerCase().replace(/^www\./, '').replace(/\.$/, ''); }
    catch (_) { return ''; }
  }

  function matchesSite(url, site) {
    const m = matcher();
    if (!site || typeof site.domain !== 'string') return false;
    return m ? m.matchesAny(url, [site.domain]) : hostMatches(getHost(url), site.domain);
  }

  function isStrict(state, t) {
    const prefs = state.cloudPrefs || {};
    const candidates = [state, prefs];
    return candidates.some(source => source.strictMode === true &&
      (!(finite(source.strictEndsAt) > 0) || finite(source.strictEndsAt) > t));
  }

  function deltaForDay(current, baseline, date) {
    const day = record(current?.[date]) ? current[date] : {};
    const before = record(baseline?.[date]) ? baseline[date] : {};
    let total = 0;
    for (const [domain, seconds] of Object.entries(day)) {
      total += Math.max(0, positiveSeconds(seconds) - positiveSeconds(before[domain]));
    }
    return total;
  }

  function allPositiveDeltas(current, baseline) {
    let total = 0;
    if (!record(current)) return total;
    for (const [date, day] of Object.entries(current)) {
      if (!record(day)) continue;
      const before = record(baseline?.[date]) ? baseline[date] : {};
      for (const [domain, seconds] of Object.entries(day)) {
        total += Math.max(0, positiveSeconds(seconds) - positiveSeconds(before[domain]));
      }
    }
    return total;
  }

  function effectiveBalance(state) {
    const cloudState = state?.cloudPolicy?.state;
    if (!cloudState) return 0;
    return Math.max(0, positiveSeconds(cloudState.creditBalanceSeconds) -
      allPositiveDeltas(state.leisureStats, state.cloudLeisureBaseline));
  }

  function selectedSite(url, state) {
    return (Array.isArray(state.cloudSites) ? state.cloudSites : [])
      .find(site => site?.isBlocked === true && matchesSite(url, site));
  }

  function policyUsage(state, date) {
    const usage = state.cloudUsage;
    return usage && usage.date === date ? usage : { date, targets: [], groups: [] };
  }

  function localTrackedDelta(state, date, key) {
    const local = record(state.stats?.[date]) ? state.stats[date] : {};
    const allBaselines = state.cloudUsageBaseline || {};
    const baseline = record(allBaselines[date]) ? allBaselines[date] : {};
    return Object.entries(local).reduce((sum, [domain, seconds]) => {
      if (!hostMatches(domain, key)) return sum;
      return sum + Math.max(0, positiveSeconds(seconds) - positiveSeconds(baseline[domain]));
    }, 0);
  }

  function groupUsageExceeded(url, state, cloudPolicy, usage) {
    const host = getHost(url);
    for (const group of Array.isArray(cloudPolicy.groups) ? cloudPolicy.groups : []) {
      const capMinutes = finite(group.dailyLimitMinutes);
      if (group.limitEnabled === false || capMinutes <= 0) continue;
      const members = Array.isArray(group.members) ? group.members : [];
      const websiteKeys = members
        .filter(member => ['website', 'site'].includes(String(member?.targetKind || '').toLowerCase()))
        .map(member => normalizedHost(member.targetKey))
        .filter(Boolean);
      if (!websiteKeys.some(key => hostMatches(host, key))) continue;

      const remote = Array.isArray(usage.groups)
        ? usage.groups.find(row => row?.groupId === group.groupId) : null;
      const remoteSeconds = positiveSeconds(remote?.trackedSeconds);
      const localDomains = record(state.stats?.[usage.date]) ? state.stats[usage.date] : {};
      const allBaselines = state.cloudUsageBaseline || {};
      const baseline = record(allBaselines[usage.date]) ? allBaselines[usage.date] : {};
      let localDelta = 0;
      for (const [domain, seconds] of Object.entries(localDomains)) {
        if (!websiteKeys.some(key => hostMatches(domain, key))) continue;
        localDelta += Math.max(0, positiveSeconds(seconds) - positiveSeconds(baseline[domain]));
      }
      if (remoteSeconds + localDelta >= capMinutes * 60) return group;
    }
    return null;
  }

  function targetLimitExceeded(url, state, cloudPolicy, usage) {
    const host = getHost(url);
    for (const limit of Array.isArray(cloudPolicy.limits) ? cloudPolicy.limits : []) {
      const kind = String(limit?.targetKind || '').toLowerCase();
      if (kind !== 'site' && kind !== 'website') continue;
      if (limit.isBlockedNow === true && hostMatches(host, limit.targetKey)) return limit;
      const capMinutes = finite(limit.dailyLimitMinutes);
      if (capMinutes <= 0 || !hostMatches(host, limit.targetKey)) continue;
      const remoteSeconds = Array.isArray(usage.targets) ? usage.targets.reduce((sum, row) => {
        if (!['site', 'website'].includes(String(row?.targetKind || '').toLowerCase()) ||
            !hostMatches(row.targetKey, limit.targetKey)) return sum;
        return sum + positiveSeconds(row.trackedSeconds);
      }, 0) : 0;
      const used = remoteSeconds + localTrackedDelta(state, usage.date, limit.targetKey);
      if (used >= capMinutes * 60) return limit;
    }
    return null;
  }

  function globalDailyCapExceeded(state, cloudState, date) {
    const capMinutes = finite(state.cloudPrefs?.globalDailyCapMinutes);
    if (capMinutes <= 0) return false;
    const remoteSeconds = cloudState.lastResetDate === date
      ? positiveSeconds(cloudState.totalScrollSecondsToday) : 0;
    const pendingLocalSpend = deltaForDay(state.leisureStats, state.cloudLeisureBaseline, date);
    return remoteSeconds + pendingLocalSpend >= capMinutes * 60;
  }

  function scheduleIsActive(schedule, t, site) {
    if (!schedule || schedule.isEnabled !== true) return false;
    const kind = String(schedule.targetKind || '').toLowerCase();
    if (kind === 'all' || kind === '*') {
      // Applies to all selected sites.
    } else if (kind === 'site' || kind === 'website') {
      if (!hostMatches(normalizedHost(site?.domain || ''), schedule.targetKey)) return false;
    } else if (kind === 'category') {
      if (!site?.category || String(site.category).toLowerCase() !== String(schedule.targetKey || '').toLowerCase()) return false;
    } else return false;

    const date = new Date(t);
    const minute = date.getHours() * 60 + date.getMinutes();
    const start = Math.max(0, Math.min(1439, Math.floor(finite(schedule.startMinute))));
    const end = Math.max(0, Math.min(1440, Math.floor(finite(schedule.endMinute))));
    const days = Array.isArray(schedule.days) ? schedule.days : [];
    if (start === end) return days.includes(date.getDay());
    if (start < end) return days.includes(date.getDay()) && minute >= start && minute < end;
    const previousDay = (date.getDay() + 6) % 7;
    return minute >= start ? days.includes(date.getDay()) : minute < end && days.includes(previousDay);
  }

  function domainSnoozed(state, host, t) {
    const domain = normalizedHost(host || '');
    const snoozes = record(state.snoozes) ? state.snoozes : {};
    const until = finite(snoozes[domain]);
    return until > t;
  }

  function block(mode, reason, listId, listName) {
    return { blocked: true, mode, reason, listId, listName };
  }

  function verdict(url, state, t) {
    state = record(state) ? state : {};
    t = Number.isFinite(t) ? t : Date.now();
    const m = matcher();
    if (!url || (m && m.isInternalUrl(url))) return null;
    const cloudPolicy = state.cloudPolicy;
    // Keep the legacy service-worker shared-boundary path authoritative until an
    // account policy snapshot has been explicitly loaded.
    if (!record(cloudPolicy)) return null;
    const host = getHost(url);
    if (!host) return null;

    if (m?.matchesAny(url, state.permanentSites || [])) {
      return block('permanent', 'permanent', '__permanent', 'Permanent block');
    }
    const strict = isStrict(state, t);
    if (strict && m?.matchesAny(url, state.strictHeldSites || [])) {
      return block('strict', 'strict', '__commitment', 'Strict Mode');
    }
    const authUrl = root.FocusLockCloud?.isAuthUrl?.(url) === true;
    if (state.cloudNuke?.isActive === true && !authUrl) {
      return block('shared-nuke', 'shared-nuclear', '__shared_nuclear', 'Shared Nuclear Block');
    }

    const date = dayKey(t);
    const usage = policyUsage(state, date);
    const matchedGroup = groupUsageExceeded(url, state, cloudPolicy, usage);
    if (matchedGroup) {
      return block('daily-limit', 'limit', `group:${matchedGroup.groupId}`, matchedGroup.name || 'Merged group limit');
    }
    const matchedLimit = targetLimitExceeded(url, state, cloudPolicy, usage);
    if (matchedLimit) {
      return block('daily-limit', 'limit', `limit:${matchedLimit.targetKind}:${matchedLimit.targetKey}`,
        matchedLimit.label || 'Daily limit');
    }

    const site = selectedSite(url, state);
    if (!site) return null;
    const cloudState = cloudPolicy.state || {};
    if (globalDailyCapExceeded(state, cloudState, date)) {
      return block('daily-limit', 'limit', '__global_daily_cap', 'Daily leisure cap');
    }
    if (strict) return block('strict', 'strict', '__shared', 'Strict Mode');

    const schedule = (Array.isArray(cloudPolicy.schedules) ? cloudPolicy.schedules : [])
      .find(row => scheduleIsActive(row, t, site));
    if (schedule) return block('schedule', 'schedule', schedule.scheduleId || '__schedule', schedule.label || 'Scheduled block');
    if (domainSnoozed(state, host, t)) return null;
    if (effectiveBalance(state) <= 0) {
      return block('earned-time', 'manual', '__shared', 'Leisure time used');
    }
    return null;
  }

  function isLeisure(url, state, t) {
    state = record(state) ? state : {};
    t = Number.isFinite(t) ? t : Date.now();
    const m = matcher();
    if (!record(state.cloudPolicy) || !url || (m && m.isInternalUrl(url))) return false;
    if (m?.matchesAny(url, state.permanentSites || [])) return false;
    const authUrl = root.FocusLockCloud?.isAuthUrl?.(url) === true;
    if (isStrict(state, t) || (state.cloudNuke?.isActive === true && !authUrl)) return false;
    const site = selectedSite(url, state);
    if (!site || domainSnoozed(state, getHost(url), t) || effectiveBalance(state) <= 0) return false;
    return verdict(url, state, t) === null;
  }

  root.FocusLockPolicy = { verdict, isLeisure, effectiveBalance };
})(typeof self !== 'undefined' ? self : globalThis);
