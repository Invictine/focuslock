const durationText = (h, minutes) => h.duration(minutes);

const actionPair = (h) => h.actions();
const hasTotals = (s) => s.scenario !== 'loading';
const hasLeisure = (s) => hasTotals(s) && s.scenario !== 'setup';
const hasTasks = (s) => !['loading', 'error', 'signedout'].includes(s.scenario);

const setupIfNeeded = (s, h) => s.scenario === 'setup' ? h.setup(s) : '';

/** Playful, poster-like dashboard with a loud orange focus block and an asymmetric grid. */
export function renderMosaic(s, h) {
  return `
    <div class="mosaic-content">
      ${h.head(s, 'Focus today')}
      ${setupIfNeeded(s, h)}
      <div class="mosaic-content-body">
        <section class="poster" aria-label="Today's focus">
          <div class="poster-topline"><span>YOUR DAY, IN BLOCKS</span><span class="poster-spark" aria-hidden="true">✳</span></div>
          <div class="poster-number">${hasTotals(s) ? durationText(h, s.focus) : '—'}<span>${hasTotals(s) ? 'FOCUSED' : 'LOADING FOCUS'}</span></div>
          <div class="poster-bottomline"><span>Goal ${hasTotals(s) ? durationText(h, s.goal) : '—'}</span><span>${hasTotals(s) && s.goal > 0 ? `${Math.round(s.focus / s.goal * 100)}% of goal` : 'Goal progress'}</span></div>
        </section>

        ${actionPair(h)}

        <div class="mosaic-grid ${hasLeisure(s) ? '' : 'without-leisure'}">
          ${hasLeisure(s) ? `<section class="tile tile-leisure">
            <div class="tile-kicker">OFF DUTY</div>
            <div class="tile-value">${durationText(h, s.leisure)}</div>
            <div class="tile-caption">leisure today</div>
            <div class="leisure-stamp" aria-hidden="true">↗</div>
          </section>` : ''}
          <section class="tile tile-ratio">
            <div class="tile-kicker">THE BALANCE</div>
            ${h.ratio(s)}
          </section>
          <section class="tile tile-task">
            <div class="tile-kicker">UP NEXT ${hasTasks(s) ? `<span class="task-counter">${s.tasksDone}/${s.tasksGoal}</span>` : ''}</div>
            ${h.task(s)}
            ${hasTasks(s) ? `<div class="task-dots" aria-label="${s.tasksDone} of ${s.tasksGoal} tasks complete">${Array.from({ length: s.tasksGoal }, (_, i) => `<i class="${i < s.tasksDone ? 'is-done' : ''}"></i>`).join('')}</div>` : ''}
          </section>
          <section class="tile tile-frog">
            <div class="tile-kicker">THE FROG</div>
            ${h.frog(s)}
          </section>
        </div>

        <section class="mosaic-wide">${h.tasks(s)}</section>
        <section class="mosaic-wide chart-tile">${h.chart(s)}</section>
        <section class="mosaic-wide">${h.history(s)}</section>
        <section class="mosaic-wide bank-tile">${h.bank(s)}</section>
        <section class="mosaic-wide">${h.usage(s)}</section>
      </div>
    </div>`;
}

/** Quiet, blue, action-first timeline with a single clear vertical reading path. */
export function renderFlow(s, h) {
  return `
    <div class="flow-content">
      ${h.head(s, 'Focus today')}
      ${setupIfNeeded(s, h)}
      <div class="flow-content-body">
        <div class="flow-intro"><span class="eyebrow">A STEADY DAY</span><span class="intro-mark" aria-hidden="true">●</span></div>

        <div class="flow-line">
          <section class="flow-step flow-now">
            <div class="step-rail"><span class="step-dot"></span><span class="step-label">NOW</span></div>
            <div class="step-content">
              <div class="flow-task-heading">One thing at a time</div>
              ${h.task(s)}
              ${actionPair(h)}
            </div>
          </section>

          <section class="flow-step flow-today">
            <div class="step-rail"><span class="step-dot"></span><span class="step-label">TODAY</span></div>
            <div class="step-content">
              <div class="day-total">${hasTotals(s) ? durationText(h, s.focus) : '—'}<span>focused · goal ${hasTotals(s) ? durationText(h, s.goal) : '—'}</span></div>
              <div class="balance-row">
                ${hasTotals(s) ? `<div class="balance-labels"><span>Focus <b>${durationText(h, s.focus)}</b></span><span>${hasLeisure(s) ? `Leisure <b>${durationText(h, s.leisure)}</b>` : 'Leisure unavailable'}</span></div>
                ${hasLeisure(s) ? `<div class="balance-track" role="img" aria-label="Focus ${s.focus} minutes, leisure ${s.leisure} minutes"><span style="--focus-share:${Math.round(s.focus / Math.max(1, s.focus + s.leisure) * 100)}%"></span></div>` : ''}` : '<div class="loading-label">Today’s balance appears when data loads</div>'}
              </div>
              <div class="ratio-fact">${h.ratio(s)}</div>
            </div>
          </section>

          <section class="flow-step flow-next">
            <div class="step-rail"><span class="step-dot"></span><span class="step-label">NEXT</span></div>
            <div class="step-content frog-content">
              <div class="flow-task-heading">Clear the big thing</div>
              ${h.frog(s)}
            </div>
          </section>
        </div>

        <section class="flow-section task-list-section"><div class="flow-section-head"><span class="eyebrow">SMALL WINS</span>${hasTasks(s) ? `<span class="task-count">${s.tasksDone} of ${s.tasksGoal}</span>` : ''}</div>${h.tasks(s)}</section>
        <section class="flow-section usage-section">${h.usage(s)}</section>
        <section class="flow-section chart-section">${h.chart(s)}</section>
        <section class="flow-section history-section">${h.history(s)}</section>
        <section class="flow-section bank-section">${h.bank(s)}</section>
      </div>
    </div>`;
}
