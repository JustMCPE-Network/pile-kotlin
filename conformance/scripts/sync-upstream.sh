#!/bin/sh
# Refresh conformance/testdata/upstream from the pinned upstream commit and
# regenerate content_hashes.txt with the upstream CLI built from that commit.
# Requires go and git.
set -eu

commit=f9a546194b364f0c13100e4024b4cc2f748b8b89
here=$(cd "$(dirname "$0")/.." && pwd)
dst="$here/testdata/upstream"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

git clone -q https://github.com/oriumgames/pile.git "$work/pile"
git -C "$work/pile" checkout -q "$commit"

rm -rf "$dst/vectors" "$dst/golden"
mkdir -p "$dst/vectors" "$dst/golden"
cp "$work/pile/format/testdata/vectors/"*.pile "$work/pile/format/testdata/vectors/"*.txt "$dst/vectors/"
cp "$work/pile/format/testdata/golden_"* "$dst/golden/"
echo "$commit" > "$dst/commit.txt"

(cd "$work/pile" && GOFLAGS=-mod=mod go build -o "$work/pile-cli" ./cmd/pile)

{
  echo "# format.ContentHash per fixture, computed by the upstream CLI at $commit."
  echo "# name  content_hash"
  for f in "$dst"/vectors/*.pile "$dst"/golden/*.pile; do
    name=$(basename "$f" .pile)
    case "$name" in neg_*) continue ;; esac
    if h=$("$work/pile-cli" hash "$f" 2>/dev/null | awk '/file/ {print $2}') && [ -n "$h" ]; then
      echo "$name $h"
      continue
    fi
    # Indexed files hash only as part of a world directory.
    dim=$(mktemp -d)
    cp "$f" "$dim/overworld.pile"
    if h=$("$work/pile-cli" hash "$dim" 2>/dev/null | awk '/overworld/ {print $2}') && [ -n "$h" ]; then
      echo "$name $h"
    fi
    rm -rf "$dim"
  done
} > "$dst/content_hashes.txt"
