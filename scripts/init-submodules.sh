#!/usr/bin/env bash
# Fetch every submodule at the exact commit recorded in this repo (shallow, one commit each).
# Plain `git submodule update --init --depth 1` would take the branch tip instead, which breaks the build.
# libyuv lives on chromium.googlesource.com; if that host is blocked, a GitHub mirror with the same commits is used.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

git config -f .gitmodules --get-regexp '\.path$' | awk '{print $2}' | while read -r path; do
  url=$(git config -f .gitmodules "submodule.${path}.url")
  sha=$(git ls-tree HEAD "$path" | awk '{print $3}')
  if [[ -e "$path/.git" && "$(git -C "$path" rev-parse HEAD 2>/dev/null || true)" == "$sha" ]]; then
    echo "ok    $path (already at ${sha:0:9})"
    continue
  fi
  mkdir -p "$path"
  [[ -e "$path/.git" ]] || git -C "$path" init -q
  urls=("$url")
  [[ "$path" == *third_party/libyuv ]] && urls+=("https://github.com/lemenkov/libyuv.git")
  done_=0
  for u in "${urls[@]}"; do
    if git -C "$path" fetch -q --depth 1 "$u" "$sha" 2>/dev/null; then done_=1; break; fi
  done
  if [[ $done_ -eq 1 ]] && git -C "$path" checkout -q "$sha"; then
    echo "ok    $path (${sha:0:9})"
  else
    echo "FAIL  $path" >&2
    exit 1
  fi
done
