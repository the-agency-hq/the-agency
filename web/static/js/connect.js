/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */

/*
 * Fills the branch field with the selected repository's default branch, on the Organization connect page.
 *
 * Convenience only, and deliberately so: the field stays a plain text input that can be typed into, and the server
 * validates whatever arrives. A page reached with JavaScript disabled still works -- it just asks the operator to
 * type "main" themselves.
 *
 * An external file rather than an inline handler because it has to be. Main installs SecurityHeaders.defaults(),
 * whose Content-Security-Policy is `default-src 'self'` with no 'unsafe-inline', so an inline onchange= is
 * silently refused. Same constraint copy.js and theme.js are written around.
 *
 * Loaded on every page, like copy.js, and does nothing on the ones that have no [data-default-branches] select.
 */
(function () {
  function apply(select, branch, force) {
    var option = select.options[select.selectedIndex];
    if (!option) {
      return;
    }

    // Never over an operator's own typing: only when the field is empty, or when they just changed the repository,
    // which makes whatever branch was in there a branch of a different repository.
    if (force || !branch.value) {
      branch.value = option.dataset.branch || '';
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    var select = document.querySelector('[data-default-branches]');
    var branch = document.getElementById('branch');
    if (!select || !branch) {
      return;
    }

    apply(select, branch, false);
    select.addEventListener('change', function () {
      apply(select, branch, true);
    });
  });
})();
