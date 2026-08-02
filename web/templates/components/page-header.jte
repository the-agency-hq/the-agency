@param String label
@param String title
@param gg.jte.Content breadcrumb = null
@param gg.jte.Content actions = null

<!-- The site's hero treatment, scaled down for a working page: dossier label above, grid backdrop behind. -->
<header class="relative mb-8">
  <div class="grid-backdrop absolute inset-0 -m-6 pointer-events-none" aria-hidden="true"></div>
  <div class="relative">
    @if(breadcrumb != null)
      <nav class="mb-3 text-sm text-slate-500 dark:text-slate-400 flex items-center gap-2">${breadcrumb}</nav>
    @endif
    <p class="dossier-label">${label}</p>
    <div class="mt-2 flex flex-wrap items-center justify-between gap-4">
      <h1 class="text-3xl font-bold tracking-tight text-slate-900 dark:text-white break-all">${title}</h1>
      @if(actions != null)
        <div class="flex items-center gap-3">${actions}</div>
      @endif
    </div>
  </div>
</header>
