@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.OrganizationConnectView

@param OrganizationConnectView model
@param User viewer

@template.layout.main(viewer = viewer, pageTitle = "Connect " + model.organization().name(), content = @`
  !{String organizationId = model.organization().id().toString();}

  @template.components.page-header(
      label = "Repository",
      title = model.organization().name(),
      breadcrumb = @`<a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}">${model.organization().name()}</a>`)

  <div class="max-w-2xl">
    <%-- The install trip ends in a redirect, so its outcome arrives as a query parameter. Rendered from a fixed
         set of known values rather than echoed, so nothing an attacker can put in the URL reaches the page. --%>
    @if("installed".equals(model.status()))
      <div class="mb-6 rounded-xl border border-emerald-200 dark:border-emerald-500/30 bg-emerald-50 dark:bg-emerald-500/10 px-4 py-3" role="status">
        <p class="text-sm text-emerald-800 dark:text-emerald-300">GitHub access updated. The repositories the app can see now are listed below.</p>
      </div>
    @elseif("install_requested".equals(model.status()))
      <div class="mb-6 rounded-xl border border-amber-200 dark:border-amber-500/30 bg-amber-50 dark:bg-amber-500/10 px-4 py-3" role="status">
        <p class="text-sm text-amber-800 dark:text-amber-300">Your request to install the app is waiting for an owner of that GitHub account to approve it. Its repositories appear here once they do.</p>
      </div>
    @endif

    @if(!model.errors().isEmpty())
      <div class="mb-6 rounded-xl border border-red-200 dark:border-red-500/30 bg-red-50 dark:bg-red-500/10 px-4 py-3" role="alert">
        <p class="text-sm font-semibold text-red-800 dark:text-red-300">This repository could not be registered:</p>
        <ul class="mt-2 list-disc list-inside space-y-1 text-sm text-red-700 dark:text-red-400">
          @for(String error : model.errors())
            <li>${error}</li>
          @endfor
        </ul>
      </div>
    @endif

    @if(model.source() != null)
      <div class="mb-6 rounded-xl border border-slate-200 dark:border-navy-800 bg-slate-50 dark:bg-navy-900/60 px-4 py-3">
        <p class="text-sm text-slate-600 dark:text-slate-300">
          Currently building from <code class="font-mono">${model.source().fullName()}</code> on <code class="font-mono">${model.source().branch()}</code>. Choosing a repository below replaces it.
        </p>
      </div>
    @endif

    <section class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h2 class="text-base font-bold text-slate-900 dark:text-white">Pick the source repository</h2>
        <p class="text-xs text-slate-500 dark:text-slate-400">
          Connected as <span class="font-mono font-semibold text-slate-700 dark:text-slate-300">${model.githubLogin()}</span>
          · <a class="text-accent-700 dark:text-accent-400 hover:underline" href="/app/oauth/github/start?organizationId=${organizationId}">reconnect</a>
        </p>
      </div>

      @if(model.repositories().isEmpty())
        <p class="mt-4 text-sm text-slate-600 dark:text-slate-300">
          This GitHub account has not given The Agency access to any repositories yet. Install the app on the account
          or organization that owns the Brief source repository. GitHub brings you back here when it is done.
        </p>
        <a href="/app/oauth/github/install?organizationId=${organizationId}"
           class="mt-5 inline-flex items-center gap-2 rounded-lg bg-slate-900 hover:bg-slate-800 dark:bg-white dark:hover:bg-slate-200 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">
          Install the GitHub app
        </a>
      @else
        <form method="post" action="/app/organizations/${organizationId}/connect" class="mt-5 space-y-6">
          <div>
            <label for="repository" class="block text-sm font-semibold text-slate-700 dark:text-slate-200">Repository</label>
            <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">Must contain a <code class="font-mono">the-agency-hq-settings.json</code> at its root.</p>
            <select id="repository" name="repository" data-default-branches
                    class="mt-2 block h-10 w-full rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-950 px-3 py-2 font-mono text-sm text-slate-900 dark:text-slate-100 focus:border-accent-500 focus:outline-none focus:ring-2 focus:ring-accent-500/30">
              @for(String fullName : model.repositories())
                <option value="${fullName}" data-branch="${model.defaultBranches().get(fullName)}" selected="${fullName.equals(model.selectedFullName())}">${fullName}</option>
              @endfor
              <optgroup label="More">
                <option value="" data-action="/app/oauth/github/install?organizationId=${organizationId}">Find more repositories...</option>
              </optgroup>
            </select>
          </div>

          <div>
            <label for="branch" class="block text-sm font-semibold text-slate-700 dark:text-slate-200">Branch</label>
            <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">Defaults to the repository's default branch.</p>
            <input type="text" id="branch" name="branch" value="${model.branch()}" placeholder="main" autocomplete="off" spellcheck="false"
                   class="mt-2 block h-10 w-full rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-950 px-3 py-2 font-mono text-sm text-slate-900 dark:text-slate-100 placeholder:text-slate-400 dark:placeholder:text-slate-500 focus:border-accent-500 focus:outline-none focus:ring-2 focus:ring-accent-500/30"/>
          </div>

          <div class="flex items-center gap-3 pt-2">
            <button type="submit" class="rounded-lg bg-accent-700 hover:bg-accent-800 dark:bg-accent-500 dark:hover:bg-accent-400 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">Use this repository</button>
            <a href="/app/organizations/${organizationId}" class="text-sm font-medium text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white transition-colors">Cancel</a>
          </div>
        </form>

        <p class="mt-6 border-t border-slate-200 dark:border-navy-800 pt-4 text-xs text-slate-500 dark:text-slate-400">
          Missing a repository? <a class="text-accent-700 dark:text-accent-400 hover:underline" href="/app/oauth/github/install?organizationId=${organizationId}">Install the app on another account, or adjust which repositories it can see</a>.
        </p>
      @endif
    </section>
  </div>
`)
