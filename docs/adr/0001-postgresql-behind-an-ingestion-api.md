# Keep PostgreSQL behind an ingestion API

PostgreSQL on the existing VPS is the canonical store for the Personal Health History, but the Android app writes through a small versioned HTTPS ingestion API instead of connecting to the database directly. This adds one service while keeping database credentials off the phone, decoupling app releases from schema changes, and centralizing validation, idempotency, and transaction handling; Grafana and authorized manual readers use separate read-only database access.
