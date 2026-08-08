@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.OrganizationsView

@param OrganizationsView model
@param User viewer

@template.layout.main(viewer = viewer, pageTitle = "Organizations", content = @`
  @template.components.page-header(
      label = "Fleet",
      title = "Organizations",
      actions = @`
        <a class="inline-flex items-center gap-2 rounded-lg bg-accent-700 hover:bg-accent-800 dark:bg-accent-500 dark:hover:bg-accent-400 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors" href="/app/organizations/new">
          <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M12 4v16m8-8H4"/></svg>
          New Organization
        </a>`)

  @if(model.rows().isEmpty())
    <div class="rounded-xl border border-dashed border-slate-300 dark:border-navy-800 px-6 py-16 text-center">
      <p class="text-sm text-slate-500 dark:text-slate-400">No Organizations yet.</p>
      <a class="mt-4 inline-block text-sm font-semibold text-accent-700 dark:text-accent-400 hover:underline" href="/app/organizations/new">Register the first one</a>
    </div>
  @else
    <div class="overflow-x-auto rounded-xl border border-slate-200 dark:border-navy-800">
      <table class="min-w-full divide-y divide-slate-200 dark:divide-navy-800 text-sm">
        <thead class="bg-slate-50 dark:bg-navy-900">
        <tr class="text-left">
          <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Name</th>
          <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Path</th>
          <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Status</th>
          <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Latest version</th>
          <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Last polled</th>
        </tr>
        </thead>
        <tbody class="divide-y divide-slate-200 dark:divide-navy-800">
        @for(OrganizationsView.Row row : model.rows())
          <tr class="hover:bg-slate-50 dark:hover:bg-navy-900/60 transition-colors align-top">
            <td class="px-4 py-3">
              <a class="font-medium text-accent-700 dark:text-accent-400 hover:underline" href="/app/organizations/${row.id().toString()}">${row.name()}</a>
            </td>
            <td class="px-4 py-3"><code class="font-mono text-xs text-slate-600 dark:text-slate-400 break-all">${row.path()}</code></td>
            <td class="px-4 py-3">
              @template.components.status-badge(status = row.status())
              @if(row.error() != null && !row.error().isBlank())
                <div class="mt-2 max-w-md text-xs text-red-700 dark:text-red-400"><span class="font-semibold">Build error:</span> ${row.error()}</div>
              @endif
              @if(row.pullError() != null && !row.pullError().isBlank())
                <div class="mt-2 max-w-md text-xs text-amber-700 dark:text-amber-400"><span class="font-semibold">Pull error:</span> ${row.pullError()}</div>
              @endif
            </td>
            <td class="px-4 py-3 font-mono tabular-nums text-slate-700 dark:text-slate-300">${row.latestVersion() == null ? "—" : row.latestVersion().toString()}</td>
            <td class="px-4 py-3 text-slate-500 dark:text-slate-400">${row.lastPolledInstant() == null ? "never" : row.lastPolledInstant().toString()}</td>
          </tr>
        @endfor
        </tbody>
      </table>
    </div>
  @endif
`)
