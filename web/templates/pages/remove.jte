@import dev.theagencyhq.agency.model.MembershipState
@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.MemberRemoveView

@param MemberRemoveView model
@param User viewer

!{boolean pending = model.member().state() == MembershipState.PENDING;}

@template.layout.main(viewer = viewer, pageTitle = pending ? "Cancel invitation" : "Remove member", content = @`
  !{String organizationId = model.organization().id().toString();}
  !{String memberName = model.member().user().email() != null ? model.member().user().email() : model.member().userId().toString();}

  @template.components.page-header(
      label = "Members",
      title = pending ? "Cancel invitation" : "Remove member",
      breadcrumb = @`
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>
        <span aria-hidden="true">/</span>
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}/members/">${model.organization().name()}</a>`)

  <div class="max-w-2xl">
    @if(!model.errors().isEmpty())
      <div class="mb-6 rounded-xl border border-red-200 dark:border-red-500/30 bg-red-50 dark:bg-red-500/10 px-4 py-3" role="alert">
        <p class="text-sm font-semibold text-red-800 dark:text-red-300">The member could not be removed:</p>
        <ul class="mt-2 list-disc list-inside space-y-1 text-sm text-red-700 dark:text-red-400">
          @for(String error : model.errors())
            <li>${error}</li>
          @endfor
        </ul>
      </div>
    @endif

    <form method="post" action="/app/organizations/${organizationId}/members/${model.member().userId().toString()}/remove" class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6 space-y-6">
      <div>
        <p class="text-sm text-slate-700 dark:text-slate-200">
          Are you sure you want to ${pending ? "cancel the invitation for" : "remove"}
          <strong class="font-semibold text-slate-900 dark:text-white">${memberName}</strong>
          (${model.member().role().label()}) from
          <strong class="font-semibold text-slate-900 dark:text-white">${model.organization().name()}</strong>?
        </p>
        <p class="mt-2 text-xs text-slate-500 dark:text-slate-400">
          ${pending ? "The invitation stops working immediately." : "They lose access immediately."} You can invite
          them ${pending ? "again" : "back"} later.
        </p>
      </div>

      <div class="flex items-center gap-3 pt-2">
        <button type="submit" class="rounded-lg bg-red-700 hover:bg-red-800 dark:bg-red-600 dark:hover:bg-red-500 px-4 py-2 text-sm font-semibold text-white transition-colors">${pending ? "Cancel invitation" : "Remove"}</button>
        <a href="/app/organizations/${organizationId}/members/" class="text-sm font-medium text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white transition-colors">Cancel</a>
      </div>
    </form>
  </div>
`)
