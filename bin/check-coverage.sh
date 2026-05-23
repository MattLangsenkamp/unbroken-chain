#!/bin/bash
# check-coverage.sh [min_statement_pct]
#
# Aggregates every module's scoverage data into one report and enforces a minimum
# aggregate statement-coverage percentage. This is the gate that blocks merges in CI.
#
# Run AFTER `./mill __.test` has produced coverage data — the instrumented test run
# (every `test` module extends ScoverageTests) writes per-module data that the root
# `scoverage` ScoverageReport module aggregates here. This script does not run tests.
#
# Args:
#     min_statement_pct : minimum aggregate statement coverage %. Defaults to
#                         $COVERAGE_MIN, then to 0 (report-only) when unset.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

MIN="${1:-${COVERAGE_MIN:-0}}"

# consoleReportAll prints the headline numbers to the log; xmlReportAll is what we parse;
# htmlReportAll is published as a CI artifact for humans to browse.
./mill scoverage.consoleReportAll
./mill scoverage.xmlReportAll
./mill scoverage.htmlReportAll

XML="out/scoverage/xmlReportAll.dest/scoverage.xml"
if [ ! -f "$XML" ]; then
  # Fall back to locating it if Mill's out-path layout shifts between versions.
  XML="$(find out/scoverage -name scoverage.xml -path '*xmlReportAll*' 2>/dev/null | head -1)"
fi
if [ -z "$XML" ] || [ ! -f "$XML" ]; then
  echo "❌ No aggregate coverage report found. Run './mill __.test' first to produce coverage data."
  exit 1
fi

# The first statement-rate in the document is the root <scoverage> aggregate;
# the rest are per-package / per-class.
COVERAGE="$(grep -o 'statement-rate="[0-9.]*"' "$XML" | head -1 | grep -o '[0-9.]*')"
if [ -z "$COVERAGE" ]; then
  echo "❌ Could not parse statement-rate from $XML"
  exit 1
fi

echo ""
echo "Aggregate statement coverage: ${COVERAGE}%  (minimum: ${MIN}%)"

# Float comparison via awk — bash can't compare decimals.
if awk -v c="$COVERAGE" -v m="$MIN" 'BEGIN { exit !(c + 0 >= m + 0) }'; then
  echo "✅ Coverage ${COVERAGE}% meets the ${MIN}% minimum"
else
  echo "❌ Coverage ${COVERAGE}% is below the ${MIN}% minimum — failing build"
  exit 1
fi
