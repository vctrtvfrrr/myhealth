# Observe a Health Record at the time its source last changed it

The `observedAt` of an envelope is the modification time Samsung Health reports for the record, not the moment the application read it. The import clock was rejected because `observedAt` is inside the canonical rendering the digest is taken over: a record nobody touched would render differently on every import, so each hourly run would store another Observed Record Version of unchanged content and the `already_present` answer the outbox relies on would never be given.

Taking it from the source also makes ADR 0012 mean what it says. Currency is decided by the largest `observed_at` over the preserved versions, so an edit at the source produces a later observation than the one it replaces and wins the projection on its own, without the ingestion having to know which observation arrived first.

A record the source reports no modification time for falls back to its start time, which is stable for the same reason. The cost is that a change Samsung Health makes without moving the modification time is invisible to this import; reflecting those belongs to the changes feed, which the capability catalog already declares and a later vertical reads.
