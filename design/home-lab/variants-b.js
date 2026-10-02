/** Warm, agenda-first home: the next action leads into a calm daily ledger. */
export function renderDaybook(s, h) {
  const countsUnavailable = ["loading", "error", "signedout"].includes(s.scenario);
  const taskCount = countsUnavailable ? "UNAVAILABLE" : `${Number(s.tasksDone) || 0} OF ${Number(s.tasksGoal) || 0}`;
  const daybookTimeline = `
    <section class="section daybook-agenda" aria-labelledby="daybook-agenda-title">
      <div class="section-heading agenda-kicker"><span>THE DAY, IN ORDER</span><span class="agenda-rule"></span></div>
      <h2 id="daybook-agenda-title" class="agenda-title">A little progress<br>adds up.</h2>
      <div class="agenda-entry agenda-entry-now">
        <div class="agenda-time"><span class="time-dot"></span><span>NOW</span></div>
        <div class="agenda-copy">
          <span class="agenda-label">NEXT UP</span>
          ${h.task(s)}
        </div>
        <span class="agenda-mark">01</span>
      </div>
      <div class="agenda-entry agenda-entry-later">
        <div class="agenda-time"><span class="time-dot"></span><span>LATER</span></div>
        <div class="agenda-copy">
          <span class="agenda-label">SMALL WIN</span>
          ${h.frog(s)}
        </div>
        <span class="agenda-mark">02</span>
      </div>
    </section>`;

  return `
    <div class="daybook-content">
      <div class="daybook-paper">
        ${h.head(s, "Focus today")}
        ${s.scenario === "setup" ? h.setup(s) : ""}
        <section class="daybook-opening" aria-label="Start or log focus">
          <div class="daybook-opening-note"><span class="opening-mark" aria-hidden="true"></span><span>MAKE ROOM FOR WHAT MATTERS</span></div>
          ${h.actions()}
        </section>
        ${daybookTimeline}
        <section class="section daybook-ledger" aria-labelledby="daybook-ledger-title">
          <div class="section-heading"><h2 id="daybook-ledger-title">Today’s ledger</h2><span class="ledger-date">A QUIET CHECK-IN</span></div>
          ${h.metrics(s)}
          <div class="daybook-ratio">
            <span class="ledger-label">HOW’S THE BALANCE?</span>
            ${h.ratio(s)}
          </div>
        </section>
        <section class="section daybook-practice" aria-label="Your work in progress">
          <div class="section-heading"><h2>Keep going</h2><span class="practice-count">${taskCount}</span></div>
          ${h.tasks(s)}
          ${h.usage(s)}
        </section>
        <section class="section daybook-reflection" aria-label="Recent focus and settings">
          <div class="section-heading"><h2>Look back</h2><span>JUST FOR YOU</span></div>
          ${h.chart(s)}
          ${h.history(s)}
          ${h.bank(s)}
        </section>
      </div>
    </div>`;
}

/** Dark instrument panel: live quantities and trends precede the work queue. */
export function renderSignal(s, h) {
  const isLoading = s.scenario === "loading";
  const countsUnavailable = ["loading", "error", "signedout"].includes(s.scenario);
  const focusValue = isLoading ? "Loading…" : `${Number(s.focus) || 0}m`;
  const leisureValue = isLoading ? "Loading…" : s.scenario === "setup" ? "Unavailable" : `${Number(s.leisure) || 0}m`;
  const goal = Number(s.goal) || 0;
  const focus = Number(s.focus) || 0;
  const progress = !isLoading && goal > 0 ? Math.max(0, Math.min(100, focus / goal * 100)) : null;
  const segmentCount = 12;
  const litCount = progress == null ? 0 : Math.round(progress / 100 * segmentCount);
  const segments = Array.from({ length: segmentCount }, (_, i) => `<span class="goal-segment${i < litCount ? " is-lit" : ""}" aria-hidden="true"></span>`).join("");
  const balanceText = isLoading ? "Loading…" : goal > 0 ? `${Math.round(Math.min(100, focus / goal * 100))}%` : "Unavailable";
  const targetText = goal > 0 ? `${goal}m` : "Unavailable";
  const tasksText = countsUnavailable ? "Unavailable" : `${Number(s.tasksDone) || 0}/${Number(s.tasksGoal) || 0} TASKS`;

  return `
    <div class="signal-content">
      <div class="signal-instrument">
        ${h.head(s, "Focus today")}
        ${s.scenario === "setup" ? h.setup(s) : ""}
        <section class="signal-start" aria-label="Start or log focus">
          <div class="signal-start-meta"><span>SESSION CONTROL</span><span class="control-line"></span></div>
          ${h.actions()}
        </section>
        <section class="section signal-readout" aria-label="Focus and leisure time">
          <div class="signal-pair">
            <article class="readout-cell readout-focus"><span class="readout-label">FOCUS</span><strong>${focusValue}</strong><span class="readout-foot">TODAY</span></article>
            <article class="readout-cell readout-leisure"><span class="readout-label">LEISURE</span><strong>${leisureValue}</strong><span class="readout-foot">TODAY</span></article>
          </div>
          <div class="goal-instrument">
            <div class="goal-line"><span>DAILY TARGET</span><strong>${targetText}</strong></div>
            <div class="goal-segments" role="img" aria-label="${progress == null ? (isLoading ? "Loading progress" : "Progress unavailable") : `${Math.round(progress)} percent of daily target`} ">${segments}</div>
            <div class="goal-caption"><span>${balanceText} COMPLETE</span><span>${tasksText}</span></div>
          </div>
          <div class="signal-ratio"><span>FOCUS / LEISURE</span>${h.ratio(s)}</div>
        </section>
        <section class="section signal-trend" aria-label="Hourly focus chart">
          <div class="section-heading"><h2>Activity signal</h2><span class="signal-index">01 / TODAY</span></div>
          ${h.chart(s)}
        </section>
        <section class="section signal-queue" aria-label="Focus task and priority">
          <div class="section-heading"><h2>Work queue</h2><span class="signal-index">02 / NEXT</span></div>
          ${h.task(s)}
          ${h.frog(s)}
          ${h.tasks(s)}
        </section>
        <section class="section signal-system" aria-label="Usage and records">
          <div class="section-heading"><h2>Session records</h2><span class="signal-index">03 / LOG</span></div>
          ${h.usage(s)}
          ${h.history(s)}
          ${h.bank(s)}
        </section>
      </div>
    </div>`;
}
