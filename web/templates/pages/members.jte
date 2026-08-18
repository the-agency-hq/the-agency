@import dev.theagencyhq.agency.model.Member
@import dev.theagencyhq.agency.model.MembershipState
@import dev.theagencyhq.agency.model.Role
@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.MembersView

@param MembersView model
@param User viewer

@template.layout.main(viewer = viewer, pageTitle = model.organization().name() + " · Members", content = @`
  !{String organizationId = model.organization().id().toString();}

  @template.components.page-header(
      label = "Members",
      title = model.organization().name(),
      breadcrumb = @`
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>
        <span aria-hidden="true">/</span>
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}">${model.organization().name()}</a>`,
      actions = @`
        <a class="inline-flex items-center gap-2 rounded-lg bg-accent-700 hover:bg-accent-800 dark:bg-accent-500 dark:hover:bg-accent-400 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors" href="/app/organizations/${organizationId}/members/invite">
          <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M12 4v16m8-8H4"/></svg>
          Invite member
        </a>`)

  <div class="overflow-x-auto rounded-xl border border-slate-200 dark:border-navy-800">
    <table class="min-w-full divide-y divide-slate-200 dark:divide-navy-800 text-sm">
      <thead class="bg-slate-50 dark:bg-navy-900">
      <tr class="text-left">
        <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Member</th>
        <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Status</th>
        <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Role</th>
        <th scope="col" class="px-4 py-3"><span class="sr-only">Actions</span></th>
      </tr>
      </thead>
      <tbody class="divide-y divide-slate-200 dark:divide-navy-800">
      @for(Member m : model.members())
        !{boolean pending = m.state() == MembershipState.PENDING;}
        !{boolean self = m.userId().equals(model.viewerUserId());}
        <tr class="hover:bg-slate-50 dark:hover:bg-navy-900/60 transition-colors align-top">
          <td class="px-4 py-3">
            <%-- The email is the identity an Owner recognizes; a member FusionAuth has no user for falls back to
                 the UUID rather than a blank cell. --%>
            <div class="font-medium text-slate-900 dark:text-white break-all">${m.user().email() != null ? m.user().email() : m.userId().toString()}</div>
          </td>
          <td class="px-4 py-3">
            @if(pending)
              <span class="inline-flex items-center rounded-md px-2 py-0.5 font-mono text-xs font-semibold tracking-wide bg-amber-50 text-amber-700 dark:bg-amber-500/10 dark:text-amber-400">INVITED</span>
            @else
              <span class="inline-flex items-center rounded-md px-2 py-0.5 font-mono text-xs font-semibold tracking-wide bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400">ACTIVE</span>
            @endif
          </td>
          <td class="px-4 py-3">
            <span class="font-medium text-slate-700 dark:text-slate-300">${m.role().label()}</span>
            @if(!pending && !self)
              <a class="ml-2 text-xs font-semibold text-accent-700 dark:text-accent-400 hover:underline" href="/app/organizations/${organizationId}/members/${m.userId().toString()}/role">Change role</a>
            @endif
          </td>
          <td class="px-4 py-3 text-right">
            @if(!self)
              @if(pending)
                <form method="post" action="/app/organizations/${organizationId}/members/${m.userId().toString()}/remove" class="inline">
                  <button type="submit" class="text-sm font-medium text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-white transition-colors">Cancel invitation</button>
                </form>
              @else
                <a class="text-sm font-medium text-red-700 dark:text-red-400 hover:underline" href="/app/organizations/${organizationId}/members/${m.userId().toString()}/remove">Remove</a>
              @endif
            @endif
          </td>
        </tr>
      @endfor
      </tbody>
    </table>
  </div>

  <div class="mt-6 grid gap-3 sm:grid-cols-2">
    @for(Role r : Role.values())
      <div class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-4">
        <p class="text-sm font-semibold text-slate-900 dark:text-white">${r.label()}</p>
        <p class="mt-1 text-xs text-slate-500 dark:text-slate-400 leading-relaxed">${r.description()}</p>
      </div>
    @endfor
  </div>
`)
