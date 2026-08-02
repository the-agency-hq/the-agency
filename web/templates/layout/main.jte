@param String pageTitle
@param gg.jte.Content content

<!doctype html>
<html lang="en" class="scroll-smooth">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>${pageTitle} · The Agency</title>
  <link rel="icon" href="/static/images/logo-mark.svg" type="image/svg+xml"/>
  <!-- Before the stylesheet and without defer, so a dark-mode reload never flashes the light theme first. It is
       an external file because SecurityHeaders' CSP is `default-src 'self'` with no 'unsafe-inline'. -->
  <script src="/static/js/theme.js"></script>
  <!-- Deferred, unlike theme.js: nothing it does is visible before first paint, and it only has to be in place by
       the time someone can click. External for the same CSP reason -- an inline onclick= would be refused. -->
  <script src="/static/js/copy.js" defer></script>
  <link rel="stylesheet" href="/static/css/app.css"/>
</head>
<body class="bg-white text-slate-900 dark:bg-navy-950 dark:text-slate-100 transition-colors duration-200">
<a href="#main" class="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:m-3 focus:px-4 focus:py-2 focus:rounded-lg focus:bg-accent-800 focus:text-white">Skip to content</a>

<nav class="sticky top-0 z-50 border-b border-slate-200 dark:border-navy-800 bg-white/80 dark:bg-navy-950/80 backdrop-blur-sm">
  <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
    <div class="flex items-center justify-between h-16">
      <a href="/app/organizations/" class="flex items-center gap-3 shrink-0" aria-label="The Agency — Organizations">
        @template.components.logo()
        <span class="hidden sm:inline text-xs font-mono font-semibold uppercase tracking-[0.2em] text-slate-400 dark:text-slate-500 border-l border-slate-200 dark:border-navy-800 pl-3">Admin</span>
      </a>
      <div class="flex items-center gap-2">
        <a href="/app/organizations/" class="text-sm font-medium text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white transition-colors px-2 py-1">Organizations</a>
        @template.components.theme-toggle()
      </div>
    </div>
  </div>
</nav>

<main id="main" class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
  ${content}
</main>

<footer class="border-t border-slate-200 dark:border-navy-800 mt-16">
  <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
    <p class="text-xs text-slate-500 dark:text-slate-400">
      The Agency — administration. Bound to localhost; every visitor here is fully privileged.
    </p>
  </div>
</footer>

</body>
</html>
