@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.OrganizationsView
@import org.lattejava.http.server.HTTPRequest

@param OrganizationsView model
@param HTTPRequest request
@param User viewer

@template.layout.main(request = request, viewer = viewer, pageTitle = "Organizations", content = @`
  @template.components.page-header(
      label = "Fleet",
      title = "Organizations",
      actions = @`
        <a class="inline-flex items-center gap-2 rounded-lg bg-accent-700 hover:bg-accent-800 dark:bg-accent-500 dark:hover:bg-accent-400 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors" href="/app/organizations/new">
          <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M12 4v16m8-8H4"/></svg>
          New Organization
        </a>`)

  <%-- The viewer's pending invitations, ahead of the listing itself: each is the same banner the Organization's
       own page shows, so accepting or declining works from either place. An invited Organization renders here and
       never as a table row -- it has no status worth one until the viewer joins. --%>
  @if(!model.invitations().isEmpty())
    <section class="mb-6 space-y-3" aria-label="Pending invitations">
      @for(OrganizationsView.Invitation invitation : model.invitations())
        @template.components.admonition(
          title = @`You've been invited to <a class="hover:underline" href="/app/organizations/${invitation.id().toString()}">${invitation.name()}</a>.`,
          content = @`Accept to join as ${invitation.role().label()}.`,
          actions = @`
            <form method="post" action="/app/organizations/${invitation.id().toString()}/members/accept">
              <button type="submit" class="rounded-lg bg-accent-700 hover:bg-accent-800 dark:bg-accent-500 dark:hover:bg-accent-400 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">Accept</button>
            </form>
            <form method="post" action="/app/organizations/${invitation.id().toString()}/members/decline">
              <button type="submit" class="rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">Decline</button>
            </form>`)
      @endfor
    </section>
  @endif

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
          <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Source</th>
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
            <td class="px-4 py-3">
              @if(row.source() == null)
                <%-- Only an Owner can reach the Sources page, so only an Owner is offered the link. --%>
                @if(row.canManage())
                  <a class="text-xs font-semibold text-accent-700 dark:text-accent-400 hover:underline" href="/app/organizations/${row.id().toString()}/sources">Connect a source</a>
                @else
                  <span class="text-xs text-slate-400 dark:text-slate-500">No source</span>
                @endif
              @else
                <div class="text-xs text-slate-400 dark:text-slate-500">${row.sourceType().label()}</div>
                <code class="font-mono text-xs text-slate-600 dark:text-slate-400 break-all">${row.source()}</code>
                @if(row.branch() != null)
                  <div class="text-xs text-slate-400 dark:text-slate-500">${row.branch()}</div>
                @endif
              @endif
            </td>
            <td class="px-4 py-3">
              @template.components.status-badge(status = row.status())
              @if(row.error() != null && !row.error().isBlank())
                <div class="mt-2 max-w-md text-xs text-red-700 dark:text-red-400"><span class="font-semibold">Error:</span> ${row.error()}</div>
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
