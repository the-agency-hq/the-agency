@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.MemberLeaveView

@param MemberLeaveView model
@param User viewer

@template.layout.main(viewer = viewer, pageTitle = "Leave " + model.organization().name(), content = @`
  !{String organizationId = model.organization().id().toString();}

  @template.components.page-header(
      label = "Membership",
      title = "Leave " + model.organization().name(),
      breadcrumb = @`
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>
        <span aria-hidden="true">/</span>
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}">${model.organization().name()}</a>`)

  <div class="max-w-2xl">
    @if(!model.errors().isEmpty())
      <div class="mb-6 rounded-xl border border-red-200 dark:border-red-500/30 bg-red-50 dark:bg-red-500/10 px-4 py-3" role="alert">
        <p class="text-sm font-semibold text-red-800 dark:text-red-300">You could not leave:</p>
        <ul class="mt-2 list-disc list-inside space-y-1 text-sm text-red-700 dark:text-red-400">
          @for(String error : model.errors())
            <li>${error}</li>
          @endfor
        </ul>
      </div>
    @endif

    <form method="post" action="/app/organizations/${organizationId}/members/leave" class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6 space-y-6">
      <div>
        <p class="text-sm text-slate-700 dark:text-slate-200">
          Are you sure you want to leave
          <strong class="font-semibold text-slate-900 dark:text-white">${model.organization().name()}</strong>
          as ${model.membership().role().label()}?
        </p>
        <p class="mt-2 text-xs text-slate-500 dark:text-slate-400">You lose access immediately. You can be re-invited later.</p>
      </div>

      <div class="flex items-center gap-3 pt-2">
        <button type="submit" class="rounded-lg bg-red-700 hover:bg-red-800 dark:bg-red-600 dark:hover:bg-red-500 px-4 py-2 text-sm font-semibold text-white transition-colors">Leave Organization</button>
        <a href="/app/organizations/${organizationId}" class="text-sm font-medium text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white transition-colors">Cancel</a>
      </div>
    </form>
  </div>
`)
