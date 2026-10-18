# Sourced by the release scripts: how Conventional Commits and release tags are read from history.

release_tag_pattern='^v[0-9]+\.[0-9]+\.[0-9]+$'
conventional_subject='^([a-z]+)(\([^)]*\))?(!)?: '

# The newest release tag reachable from a revision, optionally ignoring one tag.
latest_release_tag() {
    local revision="$1" excluded="${2:-}"
    git tag --merged "$revision" --list 'v*' |
        grep -E "$release_tag_pattern" |
        { if [[ -n "$excluded" ]]; then grep -vxF "$excluded"; else cat; fi; } |
        sort -V |
        tail -n 1 ||
        true
}

# Prints breaking, feature, fix or other for a commit that ships a change, and nothing for one that
# only touches docs, CI, tests, tooling or formatting.
classify_commit() {
    local sha="$1" subject body type
    subject="$(git log -1 --format=%s "$sha")"
    body="$(git log -1 --format=%b "$sha")"

    [[ "$subject" =~ $conventional_subject ]] || return 0
    type="${BASH_REMATCH[1]}"

    if [[ -n "${BASH_REMATCH[3]}" ]] || grep -qE '^BREAKING[ -]CHANGE: ' <<<"$body"; then
        echo breaking
        return 0
    fi

    case "$type" in
        feat) echo feature ;;
        fix) echo fix ;;
        perf | refactor | revert) echo other ;;
    esac
}

commits_since() {
    local previous="$1" revision="$2"
    if [[ -n "$previous" ]]; then
        git rev-list --no-merges --reverse "$previous..$revision"
    else
        git rev-list --no-merges --reverse "$revision"
    fi
}
