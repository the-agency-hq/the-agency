@import dev.theagencyhq.agency.model.User
@import java.util.List

@param List<String> errors = List.of()
@param String name = ""
@param User viewer

@template.layout.main(viewer = viewer, pageTitle = "New Organization", content = @`
  @template.components.page-header(
      label = "Register",
      title = "New Organization",
      breadcrumb = @`<a class="hover:text-slate-900 dark:hover:text-white transition-colors" href="/app/organizations/">Organizations</a>`)

  <div class="max-w-2xl">
    @if(!errors.isEmpty())
      <div class="mb-6 rounded-xl border border-red-200 dark:border-red-500/30 bg-red-50 dark:bg-red-500/10 px-4 py-3" role="alert">
        <p class="text-sm font-semibold text-red-800 dark:text-red-300">This Organization could not be registered:</p>
        <ul class="mt-2 list-disc list-inside space-y-1 text-sm text-red-700 dark:text-red-400">
          @for(String error : errors)
            <li>${error}</li>
          @endfor
        </ul>
      </div>
    @endif

    <form method="post" action="/app/organizations/" class="rounded-xl border border-slate-200 dark:border-navy-800 bg-white dark:bg-navy-900 p-6 space-y-6">
      <div>
        <label for="name" class="block text-sm font-semibold text-slate-700 dark:text-slate-200">Name</label>
        <p class="mt-1 text-xs text-slate-500 dark:text-slate-400">Display text. Unique, case-insensitively; stored exactly as you type it.</p>
        <input type="text" id="name" name="name" value="${name}" placeholder="Acme Tools" autocomplete="off"
               class="mt-2 block w-full rounded-lg border border-slate-300 dark:border-navy-700 bg-white dark:bg-navy-950 px-3 py-2 text-sm text-slate-900 dark:text-slate-100 placeholder:text-slate-400 dark:placeholder:text-slate-500 focus:border-accent-500 focus:outline-none focus:ring-2 focus:ring-accent-500/30"/>
      </div>

      <p class="text-xs text-slate-500 dark:text-slate-400">You will pick the GitHub repository its Briefs are built from on the next step.</p>

      <div class="flex items-center gap-3 pt-2">
        <button type="submit" class="rounded-lg bg-accent-700 hover:bg-accent-800 dark:bg-accent-500 dark:hover:bg-accent-400 px-4 py-2 text-sm font-semibold text-white dark:text-navy-950 transition-colors">Continue</button>
        <a href="/app/organizations/" class="text-sm font-medium text-slate-600 dark:text-slate-300 hover:text-slate-900 dark:hover:text-white transition-colors">Cancel</a>
      </div>
    </form>
  </div>
`)
