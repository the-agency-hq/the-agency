@import dev.theagencyhq.agency.model.Agents
@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.BriefVersionView

@param BriefVersionView model
@param User viewer

@template.layout.main(viewer = viewer, pageTitle = model.organization().name() + " v" + model.version(), content = @`
  !{String organizationId = model.organization().id().toString();}
  !{String versionBase = "/app/organizations/" + organizationId + "/versions/" + model.version();}

  @template.components.page-header(
      label = "Brief",
      title = "Version " + model.version(),
      breadcrumb = @`
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>
        <span aria-hidden="true">/</span>
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}">${model.organization().name()}</a>`)

  <section class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6">
    <dl class="grid gap-x-8 gap-y-5 sm:grid-cols-2">
      <div class="sm:col-span-2">
        <dt class="dossier-label">Checksum</dt>
        <dd class="mt-1"><code class="font-mono text-sm text-slate-700 dark:text-slate-300 break-all">${model.checksum()}</code></dd>
      </div>
      <div>
        <dt class="dossier-label">Source commit</dt>
        <dd class="mt-1"><code class="font-mono text-sm text-slate-700 dark:text-slate-300 break-all">${model.sourceCommit()}</code></dd>
      </div>
      <div>
        <dt class="dossier-label">Created</dt>
        <dd class="mt-1 text-sm text-slate-600 dark:text-slate-400">${model.insertInstant().toString()}</dd>
      </div>
      <div class="sm:col-span-2">
        <dt class="dossier-label">Agents</dt>
        <dd class="mt-1 text-sm text-slate-600 dark:text-slate-400">${Agents.describe(model.agents())}</dd>
      </div>
    </dl>
  </section>

  <h2 class="mt-10 mb-4 text-xl font-bold tracking-tight text-slate-900 dark:text-white">
    Files <span class="ml-1 font-mono text-sm font-normal text-slate-400 dark:text-slate-500">${model.entries().size()}</span>
  </h2>
  <div class="overflow-x-auto rounded-xl border border-slate-200 dark:border-navy-800">
    <table class="min-w-full divide-y divide-slate-200 dark:divide-navy-800 text-sm">
      <thead class="bg-slate-50 dark:bg-navy-900">
      <tr class="text-left">
        <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Path</th>
        <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Encoding</th>
        <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Mode</th>
        <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Size</th>
        <th scope="col" class="whitespace-nowrap px-4 py-3 font-semibold text-slate-700 dark:text-slate-200">Mission types</th>
      </tr>
      </thead>
      <tbody class="divide-y divide-slate-200 dark:divide-navy-800">
      @for(BriefVersionView.Entry entry : model.entries())
        <tr class="hover:bg-slate-50 dark:hover:bg-navy-900/60 transition-colors">
          <td class="px-4 py-3">
            <a class="font-mono text-xs text-accent-700 dark:text-accent-400 hover:underline break-all" href="${versionBase}/files/${entry.index()}">${entry.path()}</a>
          </td>
          <td class="px-4 py-3 text-slate-600 dark:text-slate-400">${entry.encoding()}</td>
          <td class="px-4 py-3"><code class="font-mono text-xs text-slate-600 dark:text-slate-400">${entry.mode()}</code></td>
          <td class="px-4 py-3 font-mono tabular-nums text-slate-600 dark:text-slate-400">${entry.size()}</td>
          <td class="px-4 py-3">
            @if(entry.missionTypes().isEmpty())
              <span class="text-slate-400 dark:text-slate-500">—</span>
            @else
              <span class="flex flex-wrap gap-1">
                @for(String missionType : entry.missionTypes())
                  <span class="inline-flex items-center rounded-md bg-brand-500/10 px-2 py-0.5 font-mono text-xs font-medium text-brand-700 dark:text-brand-400">${missionType}</span>
                @endfor
              </span>
            @endif
          </td>
        </tr>
      @endfor
      </tbody>
    </table>
  </div>
`)
