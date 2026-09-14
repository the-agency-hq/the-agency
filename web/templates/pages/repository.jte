@import dev.theagencyhq.agency.model.BriefSourceType
@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.RepositorySourceView
@import org.lattejava.http.server.HTTPRequest

@param RepositorySourceView model
@param HTTPRequest request
@param User viewer

@template.layout.main(request = request, viewer = viewer, pageTitle = "Connect " + model.organization().name(), content = @`
  !{String organizationId = model.organization().id().toString();}
  !{String label = model.type().label();}
  !{String slug = model.type().slug();}
  !{boolean gitHub = model.type() == BriefSourceType.GITHUB;}

  @template.components.page-header(
      label = label + " repository",
      title = model.organization().name(),
      breadcrumb = @`
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}">${model.organization().name()}</a>
        <span aria-hidden="true">/</span>
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}/sources">Sources</a>`)

  <div class="max-w-2xl">
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

    @if(model.config().registered())
      <div class="mb-6 rounded-xl border border-slate-200 dark:border-navy-800 bg-slate-50 dark:bg-navy-900/60 px-4 py-3">
        <p class="text-sm text-slate-600 dark:text-slate-300">
          Currently building from <code class="font-mono">${model.config().fullName()}</code> on <code class="font-mono">${model.config().branch()}</code>. Choosing a repository below replaces it.
        </p>
      </div>
    @endif

    <section class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <h2 class="text-base font-bold text-slate-900 dark:text-white">Pick the source repository</h2>
        <p class="text-xs text-slate-500 dark:text-slate-400">
          Connected as <span class="font-mono font-semibold text-slate-700 dark:text-slate-300">${model.login()}</span>
          · <a class="text-accent-700 dark:text-accent-400 hover:underline" href="/app/oauth/${slug}/start?organizationId=${organizationId}">reconnect</a>
        </p>
      </div>

      @if(model.repositories().isEmpty())
        @if(gitHub)
          <p class="mt-4 text-sm text-slate-600 dark:text-slate-300">
            This GitHub account has not given The Agency access to any repositories yet. Install the app on the account
            or organization that owns the Brief source repository. GitHub brings you back here when it is done.
          </p>
          <a href="/app/oauth/github/install?organizationId=${organizationId}"
             class="mt-5 inline-flex items-center gap-2 rounded-lg bg-slate-900 hover:bg-slate-800 dark:bg-white dark:hover:bg-slate-200 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">
            Install the GitHub app
          </a>
        @elseif(model.type() == BriefSourceType.GITLAB)
          <p class="mt-4 text-sm text-slate-600 dark:text-slate-300">
            This ${label} account is not a member of any project yet. Add it to the project that holds the Brief source
            on ${label}, then reload this page.
          </p>
        @else
          <p class="mt-4 text-sm text-slate-600 dark:text-slate-300">
            This ${label} account cannot read any repository yet. Give it access to the repository that holds the Brief
            source on ${label}, then reload this page.
          </p>
        @endif
      @else
        <form method="post" action="/app/organizations/${organizationId}/sources/${slug}" class="mt-5 space-y-6">
          <div>
            <label for="repository" class="block text-sm font-semibold text-slate-700 dark:text-slate-200">Repository</label>
            <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">Must contain a <code class="font-mono">the-agency-hq-settings.json</code> at its root.</p>
            <select id="repository" name="repository" data-default-branches
                    class="mt-2 block h-10 w-full rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-950 px-3 py-2 font-mono text-sm text-slate-900 dark:text-slate-100 focus:border-accent-500 focus:outline-none focus:ring-2 focus:ring-accent-500/30">
              @for(String fullName : model.repositories())
                <option value="${fullName}" data-branch="${model.defaultBranches().get(fullName)}" selected="${fullName.equals(model.selectedFullName())}">${fullName}</option>
              @endfor
              @if(gitHub)
                <optgroup label="More">
                  <option value="" data-action="/app/oauth/github/install?organizationId=${organizationId}">Find more repositories...</option>
                </optgroup>
              @endif
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
            <a href="/app/organizations/${organizationId}/sources" class="text-sm font-medium text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white transition-colors">Cancel</a>
          </div>
        </form>

        @if(gitHub)
          <p class="mt-6 border-t border-slate-200 dark:border-navy-800 pt-4 text-xs text-slate-500 dark:text-slate-400">
            Missing a repository? <a class="text-accent-700 dark:text-accent-400 hover:underline" href="/app/oauth/github/install?organizationId=${organizationId}">Install the app on another account, or adjust which repositories it can see</a>.
          </p>
        @elseif(model.type() == BriefSourceType.GITLAB)
          <p class="mt-6 border-t border-slate-200 dark:border-navy-800 pt-4 text-xs text-slate-500 dark:text-slate-400">
            Missing a project? The connected account has to be a member of it on ${label}. Reload this page once it is.
          </p>
        @else
          <p class="mt-6 border-t border-slate-200 dark:border-navy-800 pt-4 text-xs text-slate-500 dark:text-slate-400">
            Missing a repository? The connected account has to be able to read it on ${label}. Reload this page once it can.
          </p>
        @endif
      @endif
    </section>
  </div>
`)
