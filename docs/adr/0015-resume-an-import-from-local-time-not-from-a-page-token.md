# Resume an import from local time, not from a page token

A sync cursor stores the local time the next read starts at, and the page token Samsung Health returns is used only to walk the remaining pages of the run that obtained it. A token belongs to a read already in progress and cannot be relied on after the process dies or the device reboots, which is exactly when the initial load has to resume; a local time can always be asked for again, so persisting it is what makes the requirement "survives the application being closed and the device restarting" true rather than hoped for.

The cursor moves only over a page already staged in the outbox, and both are written in one transaction. Resuming therefore re-reads the records that share the boundary local time with the last staged page. That is the entire cost, and the ingestion is idempotent under precisely it: the re-read produces the same canonical rendering, the API answers `already_present`, and the item leaves the outbox as if it had been accepted the first time.

The initial load fixes its end when it starts, so its progress is measured against a window that does not move while it is being walked, and the incremental phase continues from that same boundary.
