#!/usr/bin/env bash

# Prints the version a revision built as <built version> still releases, read against the tags as
# they are now, or nothing when releases pushed since its build already cover all its changes.
# Fails when the revision still ships changes, but under a version other than the one it was built
# with.

set -euo pipefail

built="${1:?the version the revision was built with is required}"
revision="${2:-HEAD}"

# A run repeated after its own tag was created completes that release instead of skipping it.
own_tag="$(git rev-parse --quiet --verify "refs/tags/v$built^{commit}" || true)"
if [[ "$own_tag" == "$(git rev-parse "$revision^{commit}")" ]]; then
    echo "$built"
    exit 0
fi

current="$(bash "$(dirname "$0")/next-version.sh" "$revision")"

if [[ -z "$current" ]]; then
    echo "Built as $built, but the releases since then already cover every change; nothing to release" >&2
elif [[ "$current" != "$built" ]]; then
    echo "Built as $built, but the releases since then make this revision $current;" \
        "run the workflow again to build it as $current" >&2
    exit 1
else
    echo "$current"
fi
