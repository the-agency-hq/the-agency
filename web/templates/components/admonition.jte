@param gg.jte.Content content
@param String type = "info"

<!-- A callout box for a fact the surrounding prose should not bury. Only "info" exists so far; a new type picks
     its own colours and icon here, and the default arm makes a typo fail the render rather than fall back to a
     look that was never chosen. -->
!{String box = switch (type) {
  case "info" -> "border-cyan-200 dark:border-accent-500/30 bg-cyan-50 dark:bg-accent-500/10";
  default -> throw new IllegalArgumentException("Unknown admonition type [" + type + "]");
};}
!{String icon = switch (type) {
  case "info" -> "text-cyan-700 dark:text-accent-400";
  default -> throw new IllegalArgumentException("Unknown admonition type [" + type + "]");
};}
<div class="flex items-start gap-3 rounded-xl border px-4 py-3 ${box}" role="status">
  <svg class="w-5 h-5 shrink-0 ${icon}" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2" aria-hidden="true"><path stroke-linecap="round" stroke-linejoin="round" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z"/></svg>
  <p class="text-sm text-slate-600 dark:text-slate-300">${content}</p>
</div>
