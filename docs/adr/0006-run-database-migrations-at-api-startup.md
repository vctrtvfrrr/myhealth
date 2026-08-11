# Run database migrations at API startup

The ingestion API runs Flyway migrations during startup with a runtime database role that may execute the required DDL, and it does not begin serving requests if migration fails. This deliberately trades least-privilege separation for a self-contained deployment that matches the operation of this personal VPS; Flyway locking protects concurrent startup, and schema evolution remains versioned with the API image.
