/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */

/*
 * Copy-to-clipboard for every [data-copy] button on the page, which components/copy-button.jte renders.
 *
 * This is an external file rather than an inline onclick= because it has to be. Main installs
 * SecurityHeaders.defaults(), whose Content-Security-Policy is `default-src 'self'` with no 'unsafe-inline' --
 * an inline handler is silently refused, which shows up as a button that renders and does nothing rather than as
 * an obvious failure. Same constraint theme.js is written around.
 *
 * One delegated listener on the document, not one per button: this way a page can render any number of copy
 * buttons anywhere without the script knowing where they are, and the listener can be installed before the DOM is
 * parsed. Loaded with defer, so it runs after parsing regardless.
 */
(function () {
  // Long enough to read the confirmation, short enough that a second copy of the same value still looks like it
  // did something.
  var RESET_MILLIS = 2000;

  /**
   * Paints one of the button's three states.
   *
   * @param button    The [data-copy] button.
   * @param succeeded Whether to show the check mark. A failure keeps the clipboard icon -- a check mark next to
   *                  "Copy failed" says the opposite of what happened.
   * @param message   The visible label.
   * @param announce  Whether to write the message to the live region. The button's accessible name comes from its
   *                  aria-label, so the visible label alone is silent; but announcing the reset back to "Copy" is
   *                  noise, and only the outcome is worth interrupting for.
   */
  function paint(button, succeeded, message, announce) {
    button.querySelectorAll('.copy-icon-idle').forEach(function (icon) {
      icon.classList.toggle('hidden', succeeded);
    });
    button.querySelectorAll('.copy-icon-done').forEach(function (icon) {
      icon.classList.toggle('hidden', !succeeded);
    });

    button.querySelectorAll('.copy-button-label').forEach(function (label) {
      label.textContent = message;
    });

    button.querySelectorAll('[role="status"]').forEach(function (status) {
      status.textContent = announce ? message : '';
    });
  }

  function flash(button, succeeded, message) {
    paint(button, succeeded, message, true);

    // A rapid second click would otherwise leave the first click's timer to reset the button early.
    clearTimeout(Number(button.dataset.copyTimeout));
    button.dataset.copyTimeout = String(setTimeout(function () {
      paint(button, false, 'Copy', false);
    }, RESET_MILLIS));
  }

  document.addEventListener('click', function (event) {
    var button = event.target.closest('[data-copy]');
    if (!button) {
      return;
    }

    // navigator.clipboard exists only in a secure context. The admin UI binds to localhost, which counts as one,
    // so this is the path that runs -- but reaching the same server over a LAN address makes the API undefined,
    // and a button that reports nothing is worse than one that says it failed.
    if (!navigator.clipboard) {
      flash(button, false, 'Copy failed');
      return;
    }

    navigator.clipboard.writeText(button.dataset.copy).then(function () {
      flash(button, true, 'Copied');
    }, function () {
      flash(button, false, 'Copy failed');
    });
  });
})();
