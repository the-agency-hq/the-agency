/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */

/*
 * The Agent selection form: checking the [data-agents-all] box saves off whichever [data-agent] boxes are checked,
 * then unchecks and disables them all -- greyed out, and unambiguous about what will be saved. Unchecking All
 * enables them and re-checks the saved ones, so a selection an operator built up is not lost to a stray click. The
 * saved selection lives only on the page; leaving or submitting discards it.
 *
 * External for the same CSP reason as the other scripts here, and loaded on every page: it does nothing on a page
 * without the All box. The server treats a present `all` field as All regardless of what else arrived, so the
 * form also works with scripting off.
 */
document.addEventListener('DOMContentLoaded', () => {
  const all = document.querySelector('[data-agents-all]');
  if (!all) {
    return;
  }

  const agents = document.querySelectorAll('[data-agent]');
  let saved = [];
  const apply = () => {
    if (all.checked) {
      saved = [...agents].filter((agent) => agent.checked);
      agents.forEach((agent) => {
        agent.checked = false;
        agent.disabled = true;
      });
    } else {
      agents.forEach((agent) => {
        agent.disabled = false;
      });
      saved.forEach((agent) => {
        agent.checked = true;
      });
      saved = [];
    }
  };

  apply();
  all.addEventListener('change', apply);
});
