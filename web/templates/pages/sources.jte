@import dev.theagencyhq.agency.model.BriefSourceType
@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.OrganizationSourcesView
@import org.lattejava.http.server.HTTPRequest

@param OrganizationSourcesView model
@param HTTPRequest request
@param User viewer

@template.layout.main(request = request, viewer = viewer, pageTitle = "Sources for " + model.organization().name(), content = @`
  !{String organizationId = model.organization().id().toString();}

  @template.components.page-header(
      label = "Sources",
      title = model.organization().name(),
      breadcrumb = @`<a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}">${model.organization().name()}</a>`)

  <div class="max-w-2xl">
    @if(model.available().isEmpty())
      <div class="mb-6">
        @template.components.admonition(
          type = "warning",
          title = @`No Brief sources are configured on this server.`,
          content = @`Briefs are built from a source such as a GitHub repository, but this server has not been given the credentials for any. Whoever runs it needs to configure a source — for GitHub, <code class="font-mono">github.clientId</code> and <code class="font-mono">github.clientSecret</code>; for GitLab, <code class="font-mono">gitlab.clientId</code> and <code class="font-mono">gitlab.clientSecret</code>; for Bitbucket, <code class="font-mono">bitbucket.clientId</code> and <code class="font-mono">bitbucket.clientSecret</code> — and restart it. The README describes each.`)
      </div>
    @elseif(model.needsSource())
      <div class="mb-6">
        @template.components.admonition(
          type = "warning",
          title = @`This Organization is not connected to a Brief source.`,
          content = @`Briefs are built from a source such as a GitHub repository, and nothing can be built or updated until one is connected — or reconnected, if an earlier authorization has been revoked or has expired.`)
      </div>
    @endif

    @if(model.unavailable())
      <div class="mb-6">
        @template.components.admonition(
          type = "warning",
          title = @`This Organization's ${model.source().type().label()} source cannot be used.`,
          content = @`${model.source().type().label()} is not configured on this server, so its source is neither polled nor offered below. Configure it again to resume, or connect one of the sources that is offered — which replaces it.`)
      </div>
    @endif

    @if(!model.available().isEmpty())
      <p class="mb-6 text-sm text-slate-600 dark:text-slate-300">
        An Organization builds its Briefs from one source. Connect the kind of source that holds them, then choose what
        it should build from.
      </p>
    @endif

    <%-- One card per kind of source this server offers. The card for the kind the Organization's source is also
         reports its state: not connected, connected but not yet pointed at a repository, or building. --%>
    <div class="space-y-6">
      @for(BriefSourceType type : model.available())
        !{var config = model.config(type);}
        !{String slug = type.slug();}
        <section class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6">
          <div class="flex flex-wrap items-start justify-between gap-4">
            <div class="flex items-start gap-3">
              @template.components.source-icon(type = type)
              <div>
                <h2 class="text-base font-bold text-slate-900 dark:text-white">${type.label()}</h2>
                <p class="mt-1 text-sm text-slate-600 dark:text-slate-300">
                  @if(type == BriefSourceType.GITHUB)
                    Build Briefs from a repository on GitHub. The Agency's GitHub App reads the repositories you install it on,
                    and polls the one you choose for changes.
                  @elseif(type == BriefSourceType.GITLAB)
                    Build Briefs from a project on GitLab. The Agency reads the projects the account you authorize is a
                    member of, and polls the one you choose for changes.
                  @elseif(type == BriefSourceType.BITBUCKET)
                    Build Briefs from a repository on Bitbucket Cloud. The Agency reads the repositories the account you
                    authorize can read, and polls the one you choose for changes.
                  @endif
                </p>
              </div>
            </div>
          </div>

          <div class="mt-5 border-t border-slate-200 dark:border-navy-800 pt-5">
            @if(config == null || !config.connected())
              <div class="flex flex-wrap items-center justify-between gap-3">
                <div class="text-sm text-slate-600 dark:text-slate-300">
                  <p>Not connected.</p>
                  @if(model.replaces(type))
                    <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">Connecting ${type.label()} replaces the Organization's ${model.source().type().label()} source.</p>
                  @endif
                </div>
                <a href="/app/oauth/${slug}/start?organizationId=${organizationId}"
                   class="inline-flex items-center gap-2 rounded-lg bg-slate-900 hover:bg-slate-800 dark:bg-white dark:hover:bg-slate-200 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">
                  Connect to ${type.label()}
                </a>
              </div>
            @else
              <div class="flex flex-wrap items-center justify-between gap-3">
                <div class="text-sm text-slate-600 dark:text-slate-300">
                  <p>
                    Connected as <span class="font-mono font-semibold text-slate-700 dark:text-slate-300">${config.connection().login()}</span>
                    · <a class="text-accent-700 dark:text-accent-400 hover:underline" href="/app/oauth/${slug}/start?organizationId=${organizationId}">reconnect</a>
                  </p>
                  @if(config.registered())
                    <p class="mt-1">
                      Building from <a class="font-mono text-accent-700 dark:text-accent-400 hover:underline break-all" href="${config.url()}" target="_blank" rel="noopener">${config.fullName()}</a> on <code class="font-mono">${config.branch()}</code>.
                    </p>
                  @else
                    <p class="mt-1">No repository chosen yet, so nothing is being built.</p>
                  @endif
                </div>
                <a href="/app/organizations/${organizationId}/sources/${slug}" class="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">
                  ${config.registered() ? "Change repository" : "Connect a repository"}
                </a>
              </div>
            @endif
          </div>
        </section>
      @endfor
    </div>
  </div>
`)
