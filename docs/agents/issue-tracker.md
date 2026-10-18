# Issue tracker: Gitea

Issues and specifications for this repository live in Gitea Issues at `vctrtvfrrr/myhealth` on `https://git.codelab.tec.br`.

## Publishing

Use the authenticated `tea` CLI and infer the repository from the local Git remote. Create specifications with `tea issues create`, providing the complete Markdown body through `--description` and applying the appropriate labels through `--labels`.

When a skill says to publish to the issue tracker, create a Gitea issue in this repository. When a skill says to fetch a ticket, read the corresponding Gitea issue and its comments.

Pull requests are not an issue request surface for triage.

## Wayfinding operations

- **Network:** `tea` reaches Gitea only outside the sandbox. Use the `agent` login (`--login agent`); the `vctrtvfrrr` login's OAuth token does not refresh non-interactively.
- **Map:** an issue labelled `wayfinder:map`. Find it with `tea api --login agent '/repos/{owner}/{repo}/issues?state=open&labels=wayfinder:map&type=issues'`.
- **Child tickets:** Gitea has no native sub-issues, so a ticket names its map in a `## Parent` section of its body (`Mapa #<n>`), and carries one `wayfinder:<type>` label (`research`, `prototype`, `grilling`, `task`). Find children by searching open issues whose body contains `Mapa #<n>`.
- **Blocking:** native Gitea dependencies. `POST /repos/{owner}/{repo}/issues/{blocked}/dependencies` with `{"owner","repo","index": <blocker>}`; read them with `GET .../issues/{n}/dependencies`.
- **Claim:** assign the ticket to `vctrtvfrrr` before any work (`PATCH .../issues/{n}` with `{"assignees":["vctrtvfrrr"]}`).
- **Resolve:** post the answer as a comment, close the issue, and append a line to the map's "Decisions so far".
- **Encoding:** never round-trip an issue body through console output. On Windows, `tea` output captured by PowerShell is decoded as CP437 and a PATCH with it corrupts every accented character. Fetch with `tea api -o <file>`, read the file as UTF-8, and send bodies with `-d @<file>` written as UTF-8 without BOM.
