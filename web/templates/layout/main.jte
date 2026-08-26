@import dev.theagencyhq.agency.model.User

@param String pageTitle
@param User viewer
@param gg.jte.Content content

<!doctype html>
<html lang="en" class="scroll-smooth">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>${pageTitle} · The Agency</title>
  <link rel="icon" href="/static/images/logo-mark.svg" type="image/svg+xml"/>
  <!-- Before the stylesheet and without defer, so a dark-mode reload never flashes the light theme first. The
       canonical copy on the website, which the FusionAuth theme also loads, so all three surfaces share one
       script and one "theme" localStorage key; the CSP's script-src in Main allows the origin. -->
  <%-- crossorigin because SecurityHeaders' Cross-Origin-Embedder-Policy is require-corp: a cross-origin script
       must opt in via CORP or load in CORS mode, and the website serves access-control-allow-origin. --%>
  <script src="https://theagencyhq.dev/js/theme-0.1.0.js" crossorigin></script>
  <!-- Deferred, unlike theme.js: nothing they do is visible before first paint, and they only have to be in place
       by the time someone can click. External for the same CSP reason -- an inline onclick= would be refused.
       All are loaded on every page and each does nothing on a page without the elements it looks for, which is
       cheaper than teaching the layout which page wants which script. -->
  <script src="/static/js/copy-0.1.0.js" defer></script>
  <script src="/static/js/connect-0.1.0.js" defer></script>
  <script src="/static/js/click-action-0.1.0.js" defer></script>
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
        <a href="/app/account" class="text-sm font-medium text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white transition-colors px-2 py-1">Account</a>
        @template.components.theme-toggle()
        <div class="flex items-center gap-2 border-l border-slate-200 dark:border-navy-800 pl-2 ml-1">
          <span class="hidden sm:inline max-w-40 truncate text-sm text-slate-600 dark:text-slate-300" title="${viewer.email()}">${viewer.email()}</span>
          <!-- POST, not a link: a GET logout is followed by link prefetchers and page scanners, which would sign
               people out sideways. form-action in the CSP is 'self', and this posts to our own /logout. -->
          <form method="post" action="/logout">
            <!-- The same logout glyph the FusionAuth account pages' header uses, as an icon button styled like
                 the theme toggle beside it. -->
            <button type="submit" title="Sign out" aria-label="Sign out" class="p-2 rounded-lg text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-navy-800 transition-colors">
              <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" class="w-5 h-5" aria-hidden="true">
                <path stroke-linecap="round" stroke-linejoin="round" d="M15.75 9V5.25A2.25 2.25 0 0 0 13.5 3h-6a2.25 2.25 0 0 0-2.25 2.25v13.5A2.25 2.25 0 0 0 7.5 21h6a2.25 2.25 0 0 0 2.25-2.25V15m3 0 3-3m0 0-3-3m3 3H9"/>
              </svg>
            </button>
          </form>
        </div>
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
      The Agency — administration. Signed in as ${viewer.email()}; every registered user here is fully privileged.
    </p>
  </div>
</footer>

</body>
</html>
