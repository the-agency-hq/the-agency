<%-- Deliberately not layout.main: the layout requires a signed-in viewer for its chrome, and this page also
     renders for unmatched paths outside the authenticated /app prefix, where there is no viewer to pass. --%>
<!doctype html>
<html lang="en" class="scroll-smooth">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>Not Found · The Agency</title>
  <link rel="icon" href="/static/images/logo-mark.svg" type="image/svg+xml"/>
  <!-- Before the stylesheet and without defer, so a dark-mode reload never flashes the light theme first. -->
  <script src="https://theagencyhq.dev/js/theme-0.1.0.js" crossorigin></script>
  <link rel="stylesheet" href="/static/css/app.css"/>
</head>
<body class="bg-white text-slate-900 dark:bg-navy-950 dark:text-slate-100 transition-colors duration-200">

<nav class="sticky top-0 z-50 border-b border-slate-200 dark:border-navy-800 bg-white/80 dark:bg-navy-950/80 backdrop-blur-sm">
  <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
    <div class="flex items-center justify-between h-16">
      <a href="/app/organizations/" class="flex items-center gap-3 shrink-0" aria-label="The Agency — Organizations">
        @template.components.logo()
        <span class="hidden sm:inline text-xs font-mono font-semibold uppercase tracking-[0.2em] text-slate-400 dark:text-slate-500 border-l border-slate-200 dark:border-navy-800 pl-3">Admin</span>
      </a>
      @template.components.theme-toggle()
    </div>
  </div>
</nav>

<main id="main" class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10">
  <div class="flex min-h-[50vh] flex-col items-center justify-center text-center">
    <p class="text-xs font-mono font-semibold uppercase tracking-[0.2em] text-slate-400 dark:text-slate-500">HTTP 404</p>
    <h1 class="mt-4 text-3xl font-bold tracking-tight">Page not found</h1>
    <p class="mt-3 max-w-md text-sm text-slate-500 dark:text-slate-400">
      This page does not exist. It may have been deleted, or the address may be mistyped.
    </p>
    <a href="/app/organizations/" class="mt-8 rounded-lg bg-accent-700 hover:bg-accent-800 dark:bg-accent-500 dark:hover:bg-accent-400 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">Go to Organizations</a>
  </div>
</main>

</body>
</html>
