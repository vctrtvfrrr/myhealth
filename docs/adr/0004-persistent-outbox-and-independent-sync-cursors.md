# Use a persistent outbox and independent sync cursors

The Android app stages mapped envelopes in a persistent outbox until the ingestion API confirms each item, and advances synchronization independently for every supported Samsung Health data type. Hourly and manual synchronization use the same idempotent pipeline, allowing process death, network failure, a failed category, or a lost cursor to cause retries and extra work without silently losing or duplicating the Personal Health History.
