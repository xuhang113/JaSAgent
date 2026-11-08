#!/usr/bin/env bash
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
fail=0
for script in "$HERE"/T-*; do
  [[ -f "$script" && -x "$script" ]] || continue
  echo "RUN $(basename "$script")"
  if ! "$script"; then
    echo "FAIL $(basename "$script")"
    fail=1
  else
    echo "PASS $(basename "$script")"
  fi
done
exit "$fail"
