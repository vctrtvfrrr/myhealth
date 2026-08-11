# Declare the supported contract range instead of accepting several versions

Only one Ingestion Contract Version has ever existed, so the API publishes both bounds of a Supported Contract Range that currently coincide, and rejects every version outside it. Accepting a range of versions is deferred until a second version genuinely exists, because inventing one now would be a contract change with no demand behind it. What cannot be deferred is the declaration and the Contract Incompatibility errors: both stable codes ship now, including the one no request can reach yet, so that the arrival of a second version does not require releasing the application and the API together, which is the coupling this negotiation exists to remove.

Both bounds are published rather than only the minimum. An application newer than the API cannot otherwise tell "my version is refused" from "my version is accepted but another is advised", which is the diagnosis the unauthenticated discovery endpoint exists to give before anything is sent.

Incompatibility answers `422` and not `426`. RFC 9110 makes the `Upgrade` header mandatory in a `426` response and scopes that status to the HTTP protocol itself rather than to the version of a payload; these are well formed documents the API cannot process, and the stable code, not the status, is what tells the two remediations apart.
