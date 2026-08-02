@import dev.theagencyhq.agency.model.Brief
@import dev.theagencyhq.agency.model.view.OrganizationDetailView

@param OrganizationDetailView model

@template.layout.main(pageTitle = model.organization().name(), content = @`
  !{String organizationId = model.organization().id().toString();}

  @template.components.page-header(
      label = "Organization",
      title = model.organization().name(),
      breadcrumb = @`<a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>`,
      actions = @`
        @if(model.source() != null)
          <form method="post" action="/app/organizations/${organizationId}/rebuild">
            <button type="submit" class="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">
              <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15"/></svg>
              Rebuild now
            </button>
          </form>
        @endif`)

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
        <div class="sm:col-span-2">
          <dt class="dossier-label">Source path</dt>
          <dd class="mt-1"><code class="font-mono text-sm text-slate-700 dark:text-slate-300 break-all">${model.source().path()}</code></dd>
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
        @if(model.source().lastPullError() != null && !model.source().lastPullError().isEmpty())
          <div class="sm:col-span-2">
            <dt class="dossier-label">Last pull error</dt>
            <dd class="mt-1 text-sm text-amber-700 dark:text-amber-400">${model.source().lastPullError()}</dd>
          </div>
        @endif
      @endif
    </dl>
    <!-- Outside the <dl>, not a row inside it: a <dl> child <div> has to hold a <dt>/<dd> pair, and this is prose
         about the absence of one. -->
    @if(model.source() == null)
      <p class="mt-5 text-sm text-slate-500 dark:text-slate-400">No source is registered for this Organization.</p>
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
