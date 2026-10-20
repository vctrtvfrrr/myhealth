# Issue tracker: Gitea

Issues and specifications for this repository live in Gitea Issues at `vctrtvfrrr/myhealth`, on the server given by `GITEA_URL`.

## Publishing

Talk to Gitea through its REST API with `curl`. Always take the server and credential from the `GITEA_URL` and `GITEA_TOKEN` (full-scope access token) environment variables. Every example below assumes:

```bash
API="$GITEA_URL/api/v1"
REPO=vctrtvfrrr/myhealth
AUTH="Authorization: token $GITEA_TOKEN"
```

Create a specification with `POST $API/repos/$REPO/issues`. The body takes `title`, `body` (the complete Markdown) and `labels`, which must be numeric ids: resolve names to ids first with `GET $API/repos/$REPO/labels`.

```bash
curl -sS -H "$AUTH" "$API/repos/$REPO/labels"
curl -sS -H "$AUTH" -H "Content-Type: application/json" -X POST "$API/repos/$REPO/issues" -d @issue.json
```

When a skill says to publish to the issue tracker, create a Gitea issue in this repository. When a skill says to fetch a ticket, read the corresponding Gitea issue (`GET $API/repos/$REPO/issues/{n}`) and its comments (`GET $API/repos/$REPO/issues/{n}/comments`).

Pull requests are not an issue request surface for triage.

## Wayfinding operations

- **Map:** an issue labelled `wayfinder:map`. Find it with `GET $API/repos/$REPO/issues?state=open&labels=wayfinder:map&type=issues`.
- **Child tickets:** Gitea has no native sub-issues, so a ticket names its map in a `## Parent` section of its body (`Mapa #<n>`), and carries one `wayfinder:<type>` label (`research`, `prototype`, `grilling`, `task`). Find children by searching open issues whose body contains `Mapa #<n>` (`GET $API/repos/$REPO/issues?state=open&type=issues&q=Mapa+%23<n>`).
- **Blocking:** native Gitea dependencies. `POST $API/repos/$REPO/issues/{blocked}/dependencies` with `{"owner","repo","index": <blocker>}`; read them with `GET .../issues/{n}/dependencies`.
- **Claim:** assign the ticket to `vctrtvfrrr` before any work (`PATCH $API/repos/$REPO/issues/{n}` with `{"assignees":["vctrtvfrrr"]}`).
- **Resolve:** post the answer as a comment (`POST .../issues/{n}/comments` with `{"body": ...}`), close the issue (`PATCH .../issues/{n}` with `{"state":"closed"}`), and append a line to the map's "Decisions so far".
- **Bodies:** send JSON bodies from a file with `-d @file.json` instead of inline strings, to avoid shell quoting problems with Markdown.
