#!/bin/bash
# coverage.sh
#
# Local convenience: run the full instrumented test suite and print an aggregate
# coverage report (console + browsable HTML). Does NOT enforce a threshold — use
# check-coverage.sh (or `make coverage-check`) for the merge gate.
#
# The magnum-* repository tests use Testcontainers, so a running Docker daemon is required.
#
# Args: none

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

./mill __.test

# min = 0 → report-only, never fails. HTML lands in out/scoverage/htmlReportAll.dest/.
"$SCRIPT_DIR/check-coverage.sh" 0

echo ""
echo "📊 HTML report: out/scoverage/htmlReportAll.dest/index.html"
