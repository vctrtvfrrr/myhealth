#!/usr/bin/env bash

# Runs next-version.sh against throwaway repositories, one per case, built from the tag and
# commits each case names.

set -euo pipefail

next_version="$(cd "$(dirname "$0")" && pwd)/next-version.sh"
failures=0

# Usage: expect_version <description> <expected version, empty for none> <tag, empty for none> <commit subject>...
# A subject may carry a body after a blank line, as "subject\n\nbody".
expect_version() {
    local description="$1" expected="$2" tag="$3" repository actual
    shift 3
    repository="$(mktemp -d)"

    git -C "$repository" init --quiet
    git -C "$repository" config user.name "Release script test"
    git -C "$repository" config user.email "release-script-test@example.invalid"
    git -C "$repository" config commit.gpgsign false
    git -C "$repository" config tag.gpgsign false
    git -C "$repository" commit --quiet --allow-empty --message "chore: start"
    [[ -z "$tag" ]] || git -C "$repository" tag "$tag"

    local message
    for message in "$@"; do
        git -C "$repository" commit --quiet --allow-empty --message "$(printf '%b' "$message")"
    done

    actual="$(cd "$repository" && bash "$next_version" HEAD)"
    rm -rf "$repository"

    if [[ "$actual" == "$expected" ]]; then
        echo "ok - $description"
    else
        echo "not ok - $description: expected '${expected:-none}', got '${actual:-none}'"
        failures=$((failures + 1))
    fi
}

expect_version "a breaking type moves the major from 0.x" 1.0.0 v0.6.0 "feat!: declare the 1.0 surface"
expect_version "a breaking footer moves the major from 0.x" 1.0.0 v0.6.0 "fix: change the batch shape\n\nBREAKING CHANGE: batches drop recordType"
expect_version "a scoped breaking type moves the major from 0.x" 1.0.0 v0.6.0 "refactor(api)!: rename the endpoint"
expect_version "a breaking change moves the major from 1.x" 2.0.0 v1.4.2 "feat!: drop contract version 1"
expect_version "a breaking change outweighs features and fixes" 1.0.0 v0.6.0 "fix: a" "feat!: b" "feat: c"
expect_version "a feature moves the minor" 0.7.0 v0.6.0 "feat: import vitals"
expect_version "a feature moves the minor from 1.x" 1.5.0 v1.4.2 "fix: a" "feat: b"
expect_version "a fix moves the patch" 0.6.1 v0.6.0 "fix: keep the cursor"
expect_version "a fix moves the patch from 1.x" 1.4.3 v1.4.2 "perf: read fewer pages"
expect_version "commits that ship no change release nothing" "" v0.6.0 "docs: a" "ci: b" "test: c" "chore: d"
expect_version "the first release starts from 0.0.0" 0.1.0 "" "feat: first"

if ((failures > 0)); then
    echo "$failures release script test(s) failed" >&2
    exit 1
fi
