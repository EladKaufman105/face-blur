#!/usr/bin/env bash
# Turns a failed Gradle build's log into GitHub annotations, which anyone can read on the run page
# (the log itself needs sign-in): Kotlin compiler errors, failed unit tests, and Gradle's "What went wrong".
# GitHub shows at most 10 error annotations per step, so the first 9 errors get their own and the rest go into one.
# Usage: ci/gradle-annotate.sh <gradle log>
set -uo pipefail
log=${1:?log}
[ -f "$log" ] || exit 0
esc() { sed -e 's/%/%25/g' -e 's/\r/%0D/g' | awk 'BEGIN{ORS="%0A"} {print}'; }
root="${GITHUB_WORKSPACE:-$PWD}/"
mapfile -t errs < <(grep -E '^e: ' "$log" | sed -e "s#file://##" -e "s#$root##" | awk '!seen[$0]++')
n=0
for e in "${errs[@]}"; do
  n=$((n + 1))
  if [ "$n" -le 9 ] && [[ "$e" =~ ^e:\ ([^:]+\.kts?):([0-9]+):([0-9]+)\ (.*)$ ]]; then
    echo "::error file=${BASH_REMATCH[1]},line=${BASH_REMATCH[2]},col=${BASH_REMATCH[3]}::${BASH_REMATCH[4]}"
  fi
done
{
  [ "${#errs[@]}" -gt 0 ] && { echo "Kotlin errors (${#errs[@]}):"; printf '%s\n' "${errs[@]}" | head -60; }
  grep -E ' FAILED$' -A4 "$log" | head -60
  grep -E '^\* What went wrong:' -A12 "$log" | head -40
} > /tmp/gradle-summary.txt
[ -s /tmp/gradle-summary.txt ] && echo "::error title=Gradle build failed::$(head -c 60000 /tmp/gradle-summary.txt | esc)"
exit 0
