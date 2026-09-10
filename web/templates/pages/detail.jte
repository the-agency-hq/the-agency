@import dev.theagencyhq.agency.model.Agents
@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.Brief
@import dev.theagencyhq.agency.model.SourceDetail
@import dev.theagencyhq.agency.model.view.OrganizationDetailView
@import org.lattejava.http.server.HTTPRequest

@param OrganizationDetailView model
@param HTTPRequest request
@param User viewer

@template.layout.main(request = request, viewer = viewer, pageTitle = model.organization().name(), content = @`
  !{String organizationId = model.organization().id().toString();}

  @template.components.page-header(
      label = "Organization",
      title = model.organization().name(),
      breadcrumb = @`<a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>`,
      actions = @`
        <div class="flex flex-wrap items-center gap-3">
          @if(model.canManage())
            <a href="/app/organizations/${organizationId}/members/" class="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z"/></svg>
              Members
            </a>
            <a href="/app/organizations/${organizationId}/agents" class="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M5 3v4M3 5h4M6 17v4m-2-2h4m5-16l2.286 6.857L21 12l-5.714 2.143L13 21l-2.286-6.857L5 12l5.714-2.143L13 3z"/></svg>
              Agents
            </a>
          @endif
          @if(model.canManage())
            <a href="/app/organizations/${organizationId}/sources" class="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1"/></svg>
              Sources
            </a>
          @endif
          @if(model.registered() && model.activeMember())
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
    <div class="mb-6">
      @template.components.admonition(
        title = @`You've been invited to this Organization.`,
        content = @`Accept to join as ${model.membership().role().label()}.`,
        actions = @`
          <form method="post" action="/app/organizations/${organizationId}/members/accept">
            <button type="submit" class="rounded-lg bg-accent-700 hover:bg-accent-800 dark:bg-accent-500 dark:hover:bg-accent-400 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">Accept</button>
          </form>
          <form method="post" action="/app/organizations/${organizationId}/members/decline">
            <button type="submit" class="rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">Decline</button>
          </form>`)
    </div>
  @endif

  @if(model.needsSource())
    <div class="mb-6">
      @template.components.admonition(
        type = "warning",
        title = @`This Organization is not connected to a Brief source.`,
        content = @`Briefs are built from a source such as a GitHub repository, and nothing can be built or updated until one is connected — or reconnected, if an earlier authorization has been revoked or has expired.`,
        actions = @`
          <%-- Only an Owner can reach the Sources page -- connecting a source is Owner-gated -- so only an Owner is
               shown the way there. --%>
          @if(model.canManage())
            <a href="/app/organizations/${organizationId}/sources"
               class="inline-flex items-center gap-2 rounded-lg bg-slate-900 hover:bg-slate-800 dark:bg-white dark:hover:bg-slate-200 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">
              Connect a source
            </a>
          @endif`)
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
      <div class="sm:col-span-2">
        <dt class="dossier-label">Agents</dt>
        <dd class="mt-1 text-sm text-slate-700 dark:text-slate-300">${Agents.describe(model.organization().agents())}</dd>
      </div>
      @if(model.registered())
        <%-- The kind of source, then whatever rows the source describes itself with: the page does not know what
             a source consists of. --%>
        <div>
          <dt class="dossier-label">Source</dt>
          <dd class="mt-1 text-sm text-slate-700 dark:text-slate-300">${model.source().type().label()}</dd>
        </div>
        @for(SourceDetail detail : model.source().config().details())
          <div>
            <dt class="dossier-label">${detail.label()}</dt>
            <dd class="mt-1">
              @if(detail.url() != null)
                <a class="font-mono text-sm text-accent-700 dark:text-accent-400 hover:underline break-all" href="${detail.url()}" target="_blank" rel="noopener">${detail.value()}</a>
              @else
                <code class="font-mono text-sm text-slate-700 dark:text-slate-300 break-all">${detail.value()}</code>
              @endif
            </dd>
          </div>
        @endfor
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
    @if(!model.registered())
      <p class="mt-5 text-sm text-slate-500 dark:text-slate-400">
        No source is registered for this Organization, so nothing is being built for it.
        @if(model.canManage() && !model.needsSource())
          <a class="font-semibold text-accent-700 dark:text-accent-400 hover:underline" href="/app/organizations/${organizationId}/sources">Choose what to build from</a>.
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
