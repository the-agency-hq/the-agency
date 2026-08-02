@import dev.theagencyhq.agency.model.SourceStatus

@param SourceStatus status

<!-- A null status means the source has been registered but never polled, which is not a failure and must not be
     coloured like one. Every real status maps to a colour rather than falling through to a neutral default, so
     adding a SourceStatus without deciding how it reads here shows up as a plain grey badge at a glance. -->
!{String tone = switch (status) {
  case null -> "bg-slate-100 text-slate-600 dark:bg-navy-800 dark:text-slate-400";
  case OK -> "bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400";
  case UNCHANGED -> "bg-slate-100 text-slate-600 dark:bg-navy-800 dark:text-slate-300";
  case BUILD_FAILED -> "bg-red-50 text-red-700 dark:bg-red-500/10 dark:text-red-400";
  case NOT_A_REPOSITORY -> "bg-amber-50 text-amber-700 dark:bg-amber-500/10 dark:text-amber-400";
};}
<span class="inline-flex items-center rounded-md px-2 py-0.5 font-mono text-xs font-semibold tracking-wide ${tone}">
  ${status == null ? "NOT YET BUILT" : status.name()}
</span>
