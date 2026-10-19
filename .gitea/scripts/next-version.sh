#!/usr/bin/env bash

# Prints the version the given revision releases, or nothing when its commits since the last
# release ship no change.

set -euo pipefail

source "$(dirname "$0")/commits.sh"

revision="${1:-HEAD}"
previous="$(latest_release_tag "$revision")"

breaking=false
feature=false
releasable=false

for sha in $(commits_since "$previous" "$revision"); do
    case "$(classify_commit "$sha")" in
        breaking) breaking=true releasable=true ;;
        feature) feature=true releasable=true ;;
        fix | other) releasable=true ;;
    esac
done

$releasable || exit 0

IFS=. read -r major minor patch <<<"${previous:-v0.0.0}"
major="${major#v}"

if $breaking; then
    echo "$((major + 1)).0.0"
elif $feature; then
    echo "$major.$((minor + 1)).0"
else
    echo "$major.$minor.$((patch + 1))"
fi
