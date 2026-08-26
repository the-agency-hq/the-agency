/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */

/*
 * Navigates to the URL in an element's data-action attribute when that element is chosen: a click on any element,
 * or a change on a select whose selected option carries the attribute, since options do not receive clicks in
 * every browser.
 *
 * External rather than inline for the same reason as copy-0.1.0.js and connect-0.1.0.js: Main's Content-Security-Policy has no
 * 'unsafe-inline'. One delegated listener per event on the document, so any page can render any number of
 * [data-action] elements anywhere.
 */
const act = (element) => {
  const action = element?.dataset.action;
  if (action) {
    window.location.assign(action);
  }
};

document.addEventListener('click', (event) => act(event.target.closest('[data-action]')));
document.addEventListener('change', (event) => {
  if (event.target instanceof HTMLSelectElement) {
    act(event.target.selectedOptions[0]);
  }
});
