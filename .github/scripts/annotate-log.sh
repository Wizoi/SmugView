#!/usr/bin/env bash
# Prints the useful parts of a build log as GitHub error annotations (readable without logging in; the log itself is not).
# Usage: annotate-log.sh <log file>. Nothing here is secret: it is the build output of a public repository.
log="${1:?log file}"
[ -f "$log" ] || { echo "::error title=no log::$log was not written"; exit 0; }

emit() { # $1 = title, the text comes on stdin; newlines and percent signs are encoded the way annotations need
  local text
  text=$(cat | head -c 3800)
  text=${text//'%'/'%25'}
  text=${text//$'\r'/}
  text=${text//$'\n'/'%0A'}
  echo "::error title=$1::$text"
}

grep -E "^e: |error:|Unresolved reference|Could not resolve|Execution failed|FAILED" "$log" | head -40 | emit "compiler and task errors"
awk '/What went wrong/{f=1} f{print} /\* Try:/{exit}' "$log" | head -30 | emit "what went wrong"
tail -30 "$log" | emit "last 30 lines"
exit 0
