@import dev.theagencyhq.agency.model.User
@import dev.theagencyhq.agency.model.view.BriefFileView
@import org.lattejava.http.server.HTTPRequest

@param BriefFileView model
@param HTTPRequest request
@param User viewer

@template.layout.main(viewer = viewer, pageTitle = model.file().path(), content = @`
  !{String organizationId = model.organization().id().toString();}

  @template.components.page-header(
      label = "File",
      title = model.file().path(),
      breadcrumb = @`
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>
        <span aria-hidden="true">/</span>
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}">${model.organization().name()}</a>
        <span aria-hidden="true">/</span>
        <a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/${organizationId}/versions/${model.version()}">v${model.version()}</a>`,
      actions = @`
        <a href="${request.getPath()}?download=true" class="inline-flex items-center gap-2 rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-900 px-4 py-2 text-sm font-semibold text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-navy-800 transition-colors">
          <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"/></svg>
          Download
        </a>`)

  <section class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6">
    <dl class="grid gap-x-8 gap-y-5 sm:grid-cols-3">
      <div>
        <dt class="dossier-label">Encoding</dt>
        <dd class="mt-1 text-sm text-slate-600 dark:text-slate-400">${model.file().encoding()}</dd>
      </div>
      <div>
        <dt class="dossier-label">Mode</dt>
        <dd class="mt-1"><code class="font-mono text-sm text-slate-700 dark:text-slate-300">${model.file().mode()}</code></dd>
      </div>
      <div>
        <dt class="dossier-label">Size</dt>
        <dd class="mt-1 font-mono tabular-nums text-sm text-slate-600 dark:text-slate-400">${model.size()} bytes</dd>
      </div>
    </dl>
  </section>

  @if(model.text())
    <div class="mt-6 overflow-x-auto rounded-xl border border-slate-200 dark:border-navy-800 bg-slate-50 dark:bg-navy-950">
      <pre class="p-4 font-mono text-xs leading-relaxed text-slate-800 dark:text-slate-200"><code>${model.file().content()}</code></pre>
    </div>
  @else
    <div class="mt-6 rounded-xl border border-dashed border-slate-300 dark:border-navy-800 px-6 py-12 text-center">
      <p class="text-sm text-slate-500 dark:text-slate-400">This file is binary and cannot be previewed.</p>
      <a href="${request.getPath()}?download=true" class="mt-3 inline-block text-sm font-semibold text-accent-700 dark:text-accent-400 hover:underline">Download (${model.size()} bytes)</a>
    </div>
  @endif
`)
