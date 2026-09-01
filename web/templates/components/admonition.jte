@param gg.jte.Content actions = null
@param gg.jte.Content content
@param gg.jte.Content title = null
@param String type = "info"

<!-- A callout box for a fact the surrounding prose should not bury. The box and the icon carry the tone and the
     text stays neutral: "info" is something worth knowing, "success" the outcome of something that just worked,
     "warning" something the reader has to act on. A new type picks its own colours and icon here, and the default
     arms make a typo fail the render rather than fall back to a look that was never chosen. The optional title is
     a bold first line; the optional actions sit to the right of the text and wrap below it when there is no room. -->
!{String box = switch (type) {
  case "info" -> "border-cyan-200 dark:border-accent-500/30 bg-cyan-50 dark:bg-accent-500/10";
  case "success" -> "border-emerald-200 dark:border-emerald-500/30 bg-emerald-50 dark:bg-emerald-500/10";
  case "warning" -> "border-amber-200 dark:border-amber-500/30 bg-amber-50 dark:bg-amber-500/10";
  default -> throw new IllegalArgumentException("Unknown admonition type [" + type + "]");
};}
!{String icon = switch (type) {
  case "info" -> "text-cyan-700 dark:text-accent-400";
  case "success" -> "text-emerald-700 dark:text-emerald-400";
  case "warning" -> "text-amber-700 dark:text-amber-400";
  default -> throw new IllegalArgumentException("Unknown admonition type [" + type + "]");
};}
!{String path = switch (type) {
  case "info" -> "M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z";
  case "success" -> "M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z";
  case "warning" -> "M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z";
  default -> throw new IllegalArgumentException("Unknown admonition type [" + type + "]");
};}
!{String role = "warning".equals(type) ? "alert" : "status";}
<div class="flex flex-wrap items-center gap-x-4 gap-y-3 rounded-xl border px-4 py-3 ${box}" role="${role}">
  <div class="flex flex-1 min-w-48 items-start gap-3">
    <svg class="w-5 h-5 shrink-0 ${icon}" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="${path}"/></svg>
    <div>
      @if(title != null)
        <p class="text-sm font-semibold text-slate-900 dark:text-white">${title}</p>
        <p class="mt-1 text-sm text-slate-600 dark:text-slate-300">${content}</p>
      @else
        <p class="text-sm text-slate-600 dark:text-slate-300">${content}</p>
      @endif
    </div>
  </div>
  @if(actions != null)
    <div class="flex items-center gap-3">${actions}</div>
  @endif
</div>
