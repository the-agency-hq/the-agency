/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */

/*
 * Theme switching for the admin UI. Three states, one button: explicit light, explicit dark, or absent to follow
 * the OS. The preference lives in localStorage under "theme", which is the same key and the same three states
 * ../website uses, so a developer moving between the marketing site and the admin UI is not asked twice.
 *
 * This is an external file rather than an inline <script> because it has to be. Main installs
 * SecurityHeaders.defaults(), whose Content-Security-Policy is `default-src 'self'` with no 'unsafe-inline' --
 * an inline script is silently refused, which shows up as a theme toggle that renders nothing (all three icons
 * ship hidden and only script can unhide one) rather than as an obvious failure.
 *
 * Loaded from <head> without defer, deliberately: the first block below runs before the stylesheet is applied, so
 * a reload in dark mode never flashes the light theme first. The parts that need the DOM wait for it.
 */
(function () {
  function stored() {
    return localStorage.getItem('theme');
  }

  function apply(preference) {
    var dark = preference === 'dark'
               || (!preference && window.matchMedia('(prefers-color-scheme: dark)').matches);
    document.documentElement.classList.toggle('dark', dark);
  }

  // Before first paint. Everything below this line is deferred until the toggle exists.
  apply(stored());

  function icons(preference) {
    document.querySelectorAll('.theme-icon-light, .theme-icon-dark, .theme-icon-system').forEach(function (icon) {
      icon.classList.add('hidden');
    });

    var selector = preference === 'light' ? '.theme-icon-light'
                 : preference === 'dark' ? '.theme-icon-dark'
                 : '.theme-icon-system';
    document.querySelectorAll(selector).forEach(function (icon) {
      icon.classList.remove('hidden');
    });
  }

  function ready() {
    document.querySelectorAll('.theme-toggle').forEach(function (button) {
      button.addEventListener('click', function () {
        // light -> dark -> follow the OS -> light, matching the marketing site's cycle.
        var current = stored();
        var next = current === 'light' ? 'dark' : current === 'dark' ? null : 'light';
        if (next) {
          localStorage.setItem('theme', next);
        } else {
          localStorage.removeItem('theme');
        }

        apply(next);
        icons(next);
      });
    });

    icons(stored());
  }

  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
    if (!stored()) {
      apply(null);
    }
  });

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', ready);
  } else {
    ready();
  }
})();
