@import dev.theagencyhq.agency.model.Role
@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.MemberRoleView

@param MemberRoleView model
@param User viewer

@template.layout.main(viewer = viewer, pageTitle = "Change role", content = @`
  !{String organizationId = model.organization().id().toString();}
  !{String memberName = model.member().user().email() != null ? model.member().user().email() : model.member().userId().toString();}

  @template.components.page-header(
      label = "Members",
      title = "Change role",
      breadcrumb = @`
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>
        <span aria-hidden="true">/</span>
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}/members/">${model.organization().name()}</a>`)

  <div class="max-w-2xl">
    @if(!model.errors().isEmpty())
      <div class="mb-6 rounded-xl border border-red-200 dark:border-red-500/30 bg-red-50 dark:bg-red-500/10 px-4 py-3" role="alert">
        <p class="text-sm font-semibold text-red-800 dark:text-red-300">The role could not be changed:</p>
        <ul class="mt-2 list-disc list-inside space-y-1 text-sm text-red-700 dark:text-red-400">
          @for(String error : model.errors())
            <li>${error}</li>
          @endfor
        </ul>
      </div>
    @endif

    <form method="post" action="/app/organizations/${organizationId}/members/${model.member().userId().toString()}/role" class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6 space-y-6">
      <p class="text-sm text-slate-600 dark:text-slate-300">
        Update the role for <strong class="font-semibold text-slate-900 dark:text-white">${memberName}</strong> in
        <strong class="font-semibold text-slate-900 dark:text-white">${model.organization().name()}</strong>.
      </p>

      <fieldset>
        <legend class="block text-sm font-semibold text-slate-700 dark:text-slate-200">Role</legend>
        <div class="mt-2 grid gap-3 sm:grid-cols-2">
          @for(Role r : Role.values())
            <label class="flex cursor-pointer items-start gap-3 rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-950 p-4 has-checked:border-accent-500 has-checked:ring-2 has-checked:ring-accent-500/30 transition-colors">
              <input type="radio" name="role" value="${r.name()}" checked="${model.selectedRole() == r}" class="mt-0.5 accent-cyan-600"/>
              <span>
                <span class="block text-sm font-semibold text-slate-900 dark:text-white">${r.label()}</span>
                <span class="mt-1 block text-xs text-slate-500 dark:text-slate-400 leading-relaxed">${r.description()}</span>
              </span>
            </label>
          @endfor
        </div>
      </fieldset>

      <div class="flex items-center gap-3 pt-2">
        <button type="submit" class="rounded-lg bg-accent-700 hover:bg-accent-800 dark:bg-accent-500 dark:hover:bg-accent-400 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">Save role</button>
        <a href="/app/organizations/${organizationId}/members/" class="text-sm font-medium text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white transition-colors">Cancel</a>
      </div>
    </form>
  </div>
`)
