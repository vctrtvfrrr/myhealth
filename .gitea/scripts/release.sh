#!/usr/bin/env bash

# Publishes the release of a version: its tag on the released commit, notes grouped by kind of
# change, and the signed APK. Running it again for the same commit only completes what is missing.

set -euo pipefail

source "$(dirname "$0")/commits.sh"

version="${RELEASE_VERSION:?RELEASE_VERSION is required}"
commit="${RELEASE_COMMIT:?RELEASE_COMMIT is required}"
apk="${RELEASE_APK:?RELEASE_APK is required}"
api_url="${GITEA_API_URL:?GITEA_API_URL is required}"
repository="${GITEA_REPOSITORY:?GITEA_REPOSITORY is required}"
token="${GITEA_TOKEN:?GITEA_TOKEN is required}"

tag="v$version"
asset_name="myhealth-bridge-$version.apk"
repository_url="$api_url/repos/$repository"
auth_header="Authorization: token $token"

if [[ ! "$tag" =~ $release_tag_pattern ]]; then
    echo "Release version must be MAJOR.MINOR.PATCH: $version" >&2
    exit 1
fi

if [[ ! -f "$apk" ]]; then
    echo "Expected release APK is missing: $apk" >&2
    exit 1
fi

# Prints the body of a GET and fails on anything but 200, or prints nothing on 404.
get_or_absent() {
    local response status
    response="$(curl --silent --show-error --write-out '\n%{http_code}' --header "$auth_header" "$1")"
    status="${response##*$'\n'}"
    case "$status" in
        200) echo "${response%$'\n'*}" ;;
        404) ;;
        *)
            echo "Unexpected response $status from $1" >&2
            echo "${response%$'\n'*}" >&2
            exit 1
            ;;
    esac
}

release_notes() {
    local previous breaking="" features="" fixes="" others="" sha subject
    previous="$(latest_release_tag "$commit" "$tag")"

    for sha in $(commits_since "$previous" "$commit"); do
        subject="- $(git log -1 --format=%s "$sha")"$'\n'
        case "$(classify_commit "$sha")" in
            breaking) breaking+="$subject" ;;
            feature) features+="$subject" ;;
            fix) fixes+="$subject" ;;
            other) others+="$subject" ;;
        esac
    done

    [[ -z "$breaking" ]] || printf '## Mudanças incompatíveis\n\n%s\n' "$breaking"
    [[ -z "$features" ]] || printf '## Funcionalidades\n\n%s\n' "$features"
    [[ -z "$fixes" ]] || printf '## Correções\n\n%s\n' "$fixes"
    [[ -z "$others" ]] || printf '## Outras mudanças\n\n%s\n' "$others"
}

# Two pushes close together compute the same version; the later one must not move the tag onto
# its own commit, and the push after it computes the next version from this tag.
existing_tag="$(get_or_absent "$repository_url/tags/$tag")"
if [[ -n "$existing_tag" ]] && [[ "$(jq -r '.commit.sha' <<<"$existing_tag")" != "$commit" ]]; then
    echo "Tag $tag already points to $(jq -r '.commit.sha' <<<"$existing_tag"), not to $commit" >&2
    exit 1
fi

release_json="$(get_or_absent "$repository_url/releases/tags/$tag")"

if [[ -z "$release_json" ]]; then
    release_json="$(
        jq \
            --null-input \
            --arg tag "$tag" \
            --arg commit "$commit" \
            --arg body "$(release_notes)" \
            '{tag_name: $tag, target_commitish: $commit, name: $tag, body: $body, draft: false, prerelease: false}' |
            curl \
                --fail-with-body \
                --silent \
                --show-error \
                --request POST \
                --header "$auth_header" \
                --header "Content-Type: application/json" \
                --data @- \
                "$repository_url/releases"
    )"
    echo "Created release $tag"
fi

assets_url="$repository_url/releases/$(jq -er '.id' <<<"$release_json")/assets"
assets_json="$(curl --fail-with-body --silent --show-error --header "$auth_header" "$assets_url")"

if jq -e --arg name "$asset_name" '.[] | select(.name == $name)' <<<"$assets_json" >/dev/null; then
    echo "Release asset $asset_name already exists; nothing to publish"
    exit 0
fi

curl \
    --fail-with-body \
    --silent \
    --show-error \
    --request POST \
    --header "$auth_header" \
    --form "attachment=@$apk;type=application/vnd.android.package-archive" \
    "$assets_url?name=$asset_name" \
    >/dev/null

echo "Published $asset_name to release $tag"
