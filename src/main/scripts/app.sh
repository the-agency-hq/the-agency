#!/usr/bin/env bash
#
# Copyright (c) 2026 The Agency HQ
# SPDX-License-Identifier: MIT
#
# Runs The Agency web server from a built bundle, mirroring `latte run`.
#
# Expected layout (produced by the `bundle` target in project.latte):
#   build/bundle/app.sh   - this script
#   build/bundle/lib/     - the app jar and all runtime dependencies
#   build/bundle/web/     - the JTE templates and static assets
#
# Honors JAVA_HOME (falls back to `java` on PATH) and forwards JAVA_OPTS to the JVM.
#
# The module path is an explicit, colon-separated list of every jar in lib/ -- NOT the
# lib/ directory itself. JTE compiles templates at runtime with the in-process javac and
# derives that compiler's classpath from the jdk.module.path system property (see
# gg.jte.compiler.ClassUtils#resolveClasspathFromClassLoader). A directory on
# jdk.module.path becomes a single -classpath entry, and javac does not scan jars inside
# a classpath directory, so template compilation fails with "package gg.jte.html does not
# exist". Listing the jars explicitly -- which is what `latte run` does -- puts each jar
# on javac's classpath so JTE resolves them.
set -euo pipefail

# Resolve the bundle directory (where this script lives) and run from there, so the
# app's relative paths (`web`, the JTE `build` work dir, and config lookups) resolve.
BUNDLE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$BUNDLE_DIR"

JAVA_BIN="java"
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi

# Build a colon-separated module path from every jar in lib/.
MODULE_PATH=""
for jar in lib/*.jar; do
  MODULE_PATH="${MODULE_PATH:+$MODULE_PATH:}$jar"
done

exec "$JAVA_BIN" \
  ${JAVA_OPTS:-} \
  --module-path "$MODULE_PATH" \
  --module dev.theagencyhq.agency/dev.theagencyhq.agency.Main \
  "$@"
