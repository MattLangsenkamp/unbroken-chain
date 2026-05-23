#!/bin/bash
# verify-no-test-deps-in-deploy.sh
#
# Guards the rule that test-only code never reaches a deployable artifact.
# For every `*.server` module (the modules packaged into Docker images), inspect its
# runtime classpath and fail if it contains a test-data generators module or zio-test.
#
# Generators modules depend on zio-test, so they are test-only by construction; this script
# makes a violation a hard CI failure rather than a convention. Run from anywhere.
#
# Args: none

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# A runtime classpath entry is forbidden if it references a generators module output
# (".../generators/...") or any zio-test artifact (zio-test, zio-test-sbt, zio-test-magnolia).
# This relies on the convention that test-generator sub-modules are always named `generators`
# (so their Mill out-path contains "/generators/"). Keep that name — see the test-generators skill.
FORBIDDEN='zio-test|/generators/'

servers="$(./mill resolve __ 2>/dev/null | grep -E '\.server$' || true)"

if [ -z "$servers" ]; then
  echo "❌ No *.server modules found — is mill working?"
  exit 1
fi

fail=0
while IFS= read -r server; do
  [ -n "$server" ] || continue
  # Don't mask mill failures as a clean classpath: a crashed `show` must abort, not pass.
  if ! classpath="$(./mill show "${server}.runClasspath" 2>/dev/null)"; then
    echo "❌ failed to read runClasspath for $server — is mill working?"
    exit 1
  fi
  if [ -z "$classpath" ]; then
    echo "❌ empty runClasspath for $server — unexpected, treating as failure"
    exit 1
  fi
  if echo "$classpath" | grep -qE "$FORBIDDEN"; then
    echo "❌ $server runtime classpath contains test-only dependencies:"
    echo "$classpath" | grep -E "$FORBIDDEN" | sed 's/^/    /'
    echo ""
    fail=1
  else
    echo "✅ $server"
  fi
done <<< "$servers"

echo ""
if [ "$fail" -ne 0 ]; then
  echo "❌ Test-only generators or zio-test leaked into a deployable server classpath."
  exit 1
fi

echo "✅ No server bundles test-only generators or zio-test"
