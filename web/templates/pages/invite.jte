@import dev.theagencyhq.agency.model.Role
@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.MemberInviteView

@param MemberInviteView model
@param User viewer

@template.layout.main(viewer = viewer, pageTitle = "Invite a member", content = @`
  !{String organizationId = model.organization().id().toString();}

  @template.components.page-header(
      label = "Invite",
      title = "Invite a member",
      breadcrumb = @`
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>
        <span aria-hidden="true">/</span>
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}/members/">${model.organization().name()}</a>`)

  <div class="max-w-2xl">
    @if(!model.errors().isEmpty())
      <div class="mb-6 rounded-xl border border-red-200 dark:border-red-500/30 bg-red-50 dark:bg-red-500/10 px-4 py-3" role="alert">
        <p class="text-sm font-semibold text-red-800 dark:text-red-300">This invitation could not be sent:</p>
        <ul class="mt-2 list-disc list-inside space-y-1 text-sm text-red-700 dark:text-red-400">
          @for(String error : model.errors())
            <li>${error}</li>
          @endfor
        </ul>
      </div>
    @endif

    <form method="post" action="/app/organizations/${organizationId}/members/invite" class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6 space-y-6">
      <div>
        <label for="email" class="block text-sm font-semibold text-slate-700 dark:text-slate-200">Email</label>
        <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">They'll get an email, and stay invited until they accept.</p>
        <input type="email" id="email" name="email" value="${model.email()}" placeholder="teammate@company.com" autocomplete="off" autofocus
               class="mt-2 block w-full rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-950 px-3 py-2 text-sm text-slate-900 dark:text-slate-100 placeholder:text-slate-400 dark:placeholder:text-slate-500 focus:border-accent-500 focus:outline-none focus:ring-2 focus:ring-accent-500/30"/>
      </div>

      <fieldset>
        <legend class="block text-sm font-semibold text-slate-700 dark:text-slate-200">Role</legend>
        <div class="mt-2 grid gap-3 sm:grid-cols-2">
          @for(Role r : Role.values())
            <label class="flex cursor-pointer items-start gap-3 rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-950 p-4 has-checked:border-accent-500 has-checked:ring-2 has-checked:ring-accent-500/30 transition-colors">
              <input type="radio" name="role" value="${r.name()}" checked="${model.role() == r}" class="mt-0.5 accent-cyan-600"/>
              <span>
                <span class="block text-sm font-semibold text-slate-900 dark:text-white">${r.label()}</span>
                <span class="mt-1 block text-xs text-slate-500 dark:text-slate-400 leading-relaxed">${r.description()}</span>
              </span>
            </label>
          @endfor
        </div>
      </fieldset>

      <div class="flex items-center gap-3 pt-2">
        <button type="submit" class="rounded-lg bg-accent-700 hover:bg-accent-800 dark:bg-accent-500 dark:hover:bg-accent-400 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">Send invitation</button>
        <a href="/app/organizations/${organizationId}/members/" class="text-sm font-medium text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white transition-colors">Cancel</a>
      </div>
    </form>
  </div>
`)
