@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.Brief
@import dev.theagencyhq.agency.model.view.OrganizationDetailView

@param OrganizationDetailView model
@param User viewer

@template.layout.main(viewer = viewer, pageTitle = model.organization().name(), content = @`
  !{String organizationId = model.organization().id().toString();}

  @template.components.page-header(
      label = "Organization",
      title = model.organization().name(),
      breadcrumb = @`<a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>`,
      actions = @`
        <div class="flex flex-wrap items-center gap-3">
          @if(model.canManage())
            <a href="/app/organizations/${organizationId}/members/" class="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M17 20h5v-2a4 4 0 00-3-3.87M9 20H4v-2a4 4 0 013-3.87m6-1.13a4 4 0 10-4-4 4 4 0 004 4zm6-4a3 3 0 11-3-3"/></svg>
              Members
            </a>
          @endif
          @if(model.canManage() && !model.needsGitHubConnection())
            <a href="/app/organizations/${organizationId}/connect" class="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">
              ${model.source() == null ? "Connect a repository" : "Change repository"}
            </a>
          @endif
          @if(model.source() != null && model.activeMember())
            <form method="post" action="/app/organizations/${organizationId}/rebuild">
              <button type="submit" class="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">
                <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/></svg>
                Rebuild now
              </button>
            </form>
          @endif
          @if(model.activeMember())
            <a href="/app/organizations/${organizationId}/members/leave" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-red-700 dark:hover:text-red-400 transition-colors px-1 py-2">Leave</a>
          @endif
        </div>`)

  @if(model.invited())
    <div class="mb-6 rounded-xl border border-cyan-200 dark:border-accent-500/30 bg-cyan-50 dark:bg-accent-500/10 px-4 py-4" role="status">
      <div class="flex flex-wrap items-center gap-4">
        <div class="flex-1 min-w-48">
          <p class="text-sm font-semibold text-slate-900 dark:text-white">You've been invited to this Organization.</p>
          <p class="mt-1 text-sm text-slate-600 dark:text-slate-300">Accept to join as ${model.membership().role().label()}.</p>
        </div>
        <div class="flex items-center gap-3">
          <form method="post" action="/app/organizations/${organizationId}/members/accept">
            <button type="submit" class="rounded-lg bg-accent-700 hover:bg-accent-800 dark:bg-accent-500 dark:hover:bg-accent-400 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">Accept</button>
          </form>
          <form method="post" action="/app/organizations/${organizationId}/members/decline">
            <button type="submit" class="rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">Decline</button>
          </form>
        </div>
      </div>
    </div>
  @endif

  <%-- The OAuth round trip ends in a redirect, so the only place its outcome can be reported is a query
       parameter on the page it returns to. Rendered from a fixed set of known values rather than echoed, so
       nothing an attacker can put in the URL reaches the page. --%>
  @if("linked".equals(model.status()))
    <div class="mb-6 rounded-xl border border-emerald-200 dark:border-emerald-500/30 bg-emerald-50 dark:bg-emerald-500/10 px-4 py-3" role="status">
      <p class="text-sm text-emerald-800 dark:text-emerald-300">GitHub is connected.</p>
    </div>
  @elseif("exchange_failed".equals(model.status()))
    <div class="mb-6 rounded-xl border border-amber-200 dark:border-amber-500/30 bg-amber-50 dark:bg-amber-500/10 px-4 py-3" role="alert">
      <p class="text-sm text-amber-800 dark:text-amber-300">GitHub did not complete the authorization. This is what a declined or an already-used authorization looks like — try again.</p>
    </div>
  @endif

  @if(model.needsGitHubConnection())
    <div class="mb-6 rounded-xl border border-amber-200 dark:border-amber-500/30 bg-amber-50 dark:bg-amber-500/10 px-4 py-4" role="alert">
      <p class="text-sm font-semibold text-amber-900 dark:text-amber-200">This Organization is not connected to GitHub.</p>
      <p class="mt-1 text-sm text-amber-800 dark:text-amber-300">
        Briefs are built from a GitHub repository, and nothing can be built or updated until the Organization is
        connected — or reconnected, if an earlier authorization has been revoked or has expired.
      </p>
      <%-- Only an Owner can grant the credential -- the OAuth start route is Owner-gated -- so only an Owner is
           shown the button that starts it. --%>
      @if(model.canManage())
        <a href="/app/oauth/github/start?organizationId=${organizationId}"
           class="mt-4 inline-flex items-center gap-2 rounded-lg bg-slate-900 hover:bg-slate-800 dark:bg-white dark:hover:bg-slate-200 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">
          <svg class="w-4 h-4" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"/></svg>
          Connect to GitHub
        </a>
      @endif
    </div>
  @endif

  <!-- Outside the source check, unlike everything below it: the id is what a developer puts in a Location's
       agent-location.json, and an Organization whose source is missing is exactly when they are still setting one
       up. Hiding the id there would hide it precisely when it is needed. -->
  <section class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6">
    <dl class="grid gap-x-8 gap-y-5 sm:grid-cols-2">
      <div class="sm:col-span-2">
        <dt class="dossier-label">Organization id</dt>
        <dd class="mt-1 flex flex-wrap items-center gap-3">
          <code class="font-mono text-sm text-slate-700 dark:text-slate-300 break-all">${organizationId}</code>
          @template.components.copy-button(value = organizationId, label = "Copy Organization id")
        </dd>
      </div>
      @if(model.source() != null)
        <div>
          <dt class="dossier-label">Source repository</dt>
          <dd class="mt-1">
            <a class="font-mono text-sm text-accent-700 dark:text-accent-400 hover:underline break-all" href="${model.source().url()}" target="_blank" rel="noopener">${model.source().fullName()}</a>
          </dd>
        </div>
        <div>
          <dt class="dossier-label">Branch</dt>
          <dd class="mt-1"><code class="font-mono text-sm text-slate-700 dark:text-slate-300 break-all">${model.source().branch()}</code></dd>
        </div>
        <div>
          <dt class="dossier-label">Status</dt>
          <dd class="mt-1">@template.components.status-badge(status = model.source().lastStatus())</dd>
        </div>
        <div>
          <dt class="dossier-label">Last polled</dt>
          <dd class="mt-1 text-sm text-slate-600 dark:text-slate-400">${model.source().lastPolledInstant() == null ? "never" : model.source().lastPolledInstant().toString()}</dd>
        </div>
        @if(model.source().lastError() != null && !model.source().lastError().isEmpty())
          <div class="sm:col-span-2">
            <dt class="dossier-label">Last error</dt>
            <dd class="mt-1 text-sm text-red-700 dark:text-red-400">${model.source().lastError()}</dd>
          </div>
        @endif
      @endif
    </dl>
    <!-- Outside the <dl>, not a row inside it: a <dl> child <div> has to hold a <dt>/<dd> pair, and this is prose
         about the absence of one. -->
    @if(model.source() == null)
      <p class="mt-5 text-sm text-slate-500 dark:text-slate-400">
        No source is registered for this Organization, so nothing is being built for it.
        @if(model.canManage() && !model.needsGitHubConnection())
          <a class="font-semibold text-accent-700 dark:text-accent-400 hover:underline" href="/app/organizations/${organizationId}/connect">Connect a GitHub repository</a>.
        @endif
      </p>
    @endif
  </section>

  <h2 class="mt-10 mb-4 text-xl font-bold tracking-tight text-slate-900 dark:text-white">Versions</h2>
  @if(model.versions().isEmpty())
    <div class="rounded-xl border border-dashed border-slate-300 dark:border-navy-800 px-6 py-12 text-center">
      <p class="text-sm text-slate-500 dark:text-slate-400">No Brief versions yet.</p>
    </div>
  @else
    <div class="overflow-x-auto rounded-xl border border-slate-200 dark:border-navy-800">
      <table class="min-w-full divide-y divide-slate-200 dark:divide-navy-800 text-sm">
        <thead class="bg-slate-50 dark:bg-navy-900">
        <tr class="text-left">
          <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Version</th>
          <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Checksum</th>
          <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Source commit</th>
          <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Created</th>
        </tr>
        </thead>
        <tbody class="divide-y divide-slate-200 dark:divide-navy-800">
        @for(Brief v : model.versions())
          <tr class="hover:bg-slate-50 dark:hover:bg-navy-900/60 transition-colors">
            <td class="px-4 py-3">
              <a class="font-mono font-semibold tabular-nums text-accent-700 dark:text-accent-400 hover:underline" href="/app/organizations/${organizationId}/versions/${v.version()}">${v.version()}</a>
            </td>
            <td class="px-4 py-3"><code class="font-mono text-xs text-slate-600 dark:text-slate-400 break-all">${v.checksum()}</code></td>
            <td class="px-4 py-3"><code class="font-mono text-xs text-slate-600 dark:text-slate-400 break-all">${v.sourceCommit()}</code></td>
            <td class="px-4 py-3 text-slate-500 dark:text-slate-400">${v.insertInstant().toString()}</td>
          </tr>
        @endfor
        </tbody>
      </table>
    </div>
  @endif
`)
