#!/bin/sh
# Tags the current commit <product>-v<version> and creates its GitHub release, with notes listing the commits that
# touched the product's folders since its previous release. Used by the workflows after a successful publish.
#   scripts/github-release.sh <product> <title> <latest:true|false> <path>...      (needs GH_TOKEN)
# ASSETS=<dir> attaches every file in that folder (the desktop packages) in the same step, so the release never
# exists without them (the desktop app's updater reads latest*.yml from the newest desktop-v* release).
set -e
product=$1 title=$2 latest=$3
shift 3
version=$(scripts/version.py get "$product")
tag="$product-v$version"
prev=$(git tag --list "$product-v*" --sort=-v:refname | head -1)
# Before the split, server and Android shared plain vX.Y.Z tags.
[ -z "$prev" ] && { [ "$product" = server ] || [ "$product" = android ]; } && prev=$(git tag --list 'v[0-9]*' --sort=-v:refname | head -1)
{
  echo "## What's changed"
  git log --no-merges --format='- %s (%h)' ${prev:+"$prev"..}HEAD -- "$@"
  [ -n "$prev" ] && echo && echo "Since $prev."
} > release-notes.md
gh release create "$tag" ${ASSETS:+"$ASSETS"/*} --target "$GITHUB_SHA" --title "$title $version" --notes-file release-notes.md --latest="$latest"
echo "Released $tag"
