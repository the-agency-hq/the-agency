/*
 * Copyright (c) 2026 The Agency HQ
 * SPDX-License-Identifier: MIT
 */

/*
 * A typed confirmation: the [data-confirm-submit] button in a form is enabled only while the form's [data-confirm]
 * input holds exactly the attribute's value, whitespace around it ignored as the server ignores it.
 *
 * External for the same CSP reason as the other scripts here, and loaded on every page: it does nothing on a page
 * without the input. The markup renders the button enabled and the server checks the value regardless, so the form
 * works with scripting off.
 */
document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('[data-confirm]').forEach((input) => {
    const submit = input.form?.querySelector('[data-confirm-submit]');
    if (!submit) {
      return;
    }

    const apply = () => {
      submit.disabled = input.value.trim() !== input.dataset.confirm;
    };

    apply();
    input.addEventListener('input', apply);
  });
});
