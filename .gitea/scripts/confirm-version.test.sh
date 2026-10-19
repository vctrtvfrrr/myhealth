#!/usr/bin/env bash

# Runs confirm-version.sh against throwaway repositories, one per case, built from the commits and
# tags each case names in order.

set -euo pipefail

confirm_version="$(cd "$(dirname "$0")" && pwd)/confirm-version.sh"
failures=0

# Usage: expect_confirmed <description> <expected version, "fail" or empty for none> <built version> <step>...
# A step is "tag <name>", tagging the commit before it, or a commit subject.
expect_confirmed() {
    local description="$1" expected="$2" built="$3" repository actual step
    shift 3
    repository="$(mktemp -d)"

    git -C "$repository" init --quiet
    git -C "$repository" config user.name "Release script test"
    git -C "$repository" config user.email "release-script-test@example.invalid"
    git -C "$repository" config commit.gpgsign false
    git -C "$repository" config tag.gpgsign false
    git -C "$repository" commit --quiet --allow-empty --message "chore: start"

    for step in "$@"; do
        if [[ "$step" == "tag "* ]]; then
            git -C "$repository" tag "${step#tag }"
        else
            git -C "$repository" commit --quiet --allow-empty --message "$step"
        fi
    done

    actual="$(cd "$repository" && bash "$confirm_version" "$built" HEAD 2>/dev/null)" || actual=fail
    rm -rf "$repository"

    if [[ "$actual" == "$expected" ]]; then
        echo "ok - $description"
    else
        echo "not ok - $description: expected '${expected:-none}', got '${actual:-none}'"
        failures=$((failures + 1))
    fi
}

expect_confirmed "no release since the build keeps the built version" 0.7.0 0.7.0 \
    "tag v0.6.0" "feat: import vitals"
expect_confirmed "a release covering every change since the build releases nothing" "" 0.7.0 \
    "tag v0.6.0" "feat: import vitals" "tag v0.7.0" "docs: extend the smoke test"
expect_confirmed "a release covering part of the changes fails the build's version" fail 0.7.0 \
    "tag v0.6.0" "feat: import vitals" "tag v0.7.0" "feat: import sleep"
expect_confirmed "a repeated run whose own tag exists completes its release" 0.7.0 0.7.0 \
    "tag v0.6.0" "feat: import vitals" "tag v0.7.0"

if ((failures > 0)); then
    echo "$failures release script test(s) failed" >&2
    exit 1
fi
