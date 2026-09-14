@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.OrganizationDeleteView
@import org.lattejava.http.server.HTTPRequest

@param OrganizationDeleteView model
@param HTTPRequest request
@param User viewer

@template.layout.main(request = request, viewer = viewer, pageTitle = "Delete " + model.organization().name(), content = @`
  !{String organizationId = model.organization().id().toString();}
  !{String name = model.organization().name();}

  @template.components.page-header(
      label = "Organization",
      title = "Delete " + name,
      breadcrumb = @`
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>
        <span aria-hidden="true">/</span>
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}">${name}</a>`)

  <div class="max-w-2xl">
    @if(!model.errors().isEmpty())
      <div class="mb-6 rounded-xl border border-red-200 dark:border-red-500/30 bg-red-50 dark:bg-red-500/10 px-4 py-3" role="alert">
        <p class="text-sm font-semibold text-red-800 dark:text-red-300">The Organization could not be deleted:</p>
        <ul class="mt-2 list-disc list-inside space-y-1 text-sm text-red-700 dark:text-red-400">
          @for(String error : model.errors())
            <li>${error}</li>
          @endfor
        </ul>
      </div>
    @endif

    <div class="mb-6">
      @template.components.admonition(
        type = "warning",
        title = @`Deleting an Organization cannot be undone.`,
        content = @`Everything below is removed at once, and every Handler polling this Organization is served nothing for it from its next poll on.`)
    </div>

    <form method="post" action="/app/organizations/${organizationId}/delete" class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6 space-y-6">
      <div>
        <p class="text-sm font-semibold text-slate-900 dark:text-white">What is deleted</p>
        <ul class="mt-2 list-disc list-inside space-y-1 text-sm text-slate-700 dark:text-slate-300">
          <li>The Organization <strong class="font-semibold text-slate-900 dark:text-white">${name}</strong> and its id.</li>
          <li>
            @if(model.source() == null)
              Its Brief source: none is connected.
            @elseif(model.source().registered())
              Its link to ${model.source().type().label()} source <code class="font-mono text-slate-900 dark:text-white">${model.source().source()}</code>, together with the authorization that polls it.
            @else
              Its ${model.source().type().label()} authorization, which is connected but not yet registered to a repository.
            @endif
          </li>
          <li>${model.versions()} Brief ${model.versions() == 1 ? "version" : "versions"}, files included.</li>
          <li>${model.members()} ${model.members() == 1 ? "membership" : "memberships"}, yours included. Invitations that have not been accepted stop working.</li>
        </ul>
      </div>

      <div>
        <label for="name" class="block text-sm font-semibold text-slate-700 dark:text-slate-200">Type <span class="font-mono">${name}</span> to confirm</label>
        <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">The full name, exactly as it appears.</p>
        <input type="text" id="name" name="name" value="${model.confirmation()}" autocomplete="off" autofocus data-confirm="${name}"
               class="mt-2 block w-full rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-950 px-3 py-2 text-sm text-slate-900 dark:text-slate-100 focus:border-red-500 focus:outline-none focus:ring-2 focus:ring-red-500/30"/>
      </div>

      <div class="flex items-center gap-3 pt-2">
        <button type="submit" data-confirm-submit class="rounded-lg bg-red-700 hover:bg-red-800 dark:bg-red-600 dark:hover:bg-red-500 px-4 py-2 text-sm font-semibold text-white transition-colors disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-red-700 dark:disabled:hover:bg-red-600">Delete Organization</button>
        <a href="/app/organizations/${organizationId}" class="text-sm font-medium text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white transition-colors">Cancel</a>
      </div>
    </form>
  </div>
`)
