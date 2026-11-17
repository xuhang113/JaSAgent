#!/usr/bin/env bash
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
failed=0
for case in T-001 T-002 T-003 T-004 T-005 T-006 T-007 T-008; do
  echo "running $case"
  if ! "$HERE/$case"; then
    failed=1
  fi
done
exit $failed
