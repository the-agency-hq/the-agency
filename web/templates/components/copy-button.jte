@param String value
@param String label

<!-- Copies `value` to the clipboard. The click handler lives in /static/js/copy.js and finds this button by its
     data-copy attribute rather than an onclick=: SecurityHeaders' CSP is `default-src 'self'` with no
     'unsafe-inline', which refuses an inline handler silently, so the button would render and do nothing.

     Both icons ship in the markup and the script swaps which one is hidden, for the same reason theme-toggle.jte
     does it -- the server has no idea what state the button is in, and CSP rules out building one on the fly. The
     role="status" span is empty until a click: the visible label is aria-hidden because the button's accessible
     name comes from aria-label, so without a live region a screen reader hears nothing when the copy succeeds. -->
<button type="button" class="copy-button inline-flex items-center gap-1.5 rounded-md border border-slate-300 dark:border-navy-700 px-2 py-1 text-xs font-semibold text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-navy-800 focus:outline-none focus:ring-2 focus:ring-accent-500/30 transition-colors" data-copy="${value}" aria-label="${label}">
  <svg class="copy-icon-idle w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
    <path stroke-linecap="round" stroke-linejoin="round" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"/>
  </svg>
  <svg class="copy-icon-done hidden w-4 h-4 text-emerald-700 dark:text-emerald-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true">
    <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7"/>
  </svg>
  <span class="copy-button-label" aria-hidden="true">Copy</span>
  <span class="sr-only" role="status"></span>
</button>
