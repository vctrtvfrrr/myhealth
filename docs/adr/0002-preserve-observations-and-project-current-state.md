# Preserve observations and project current state

Every distinct version observed from Samsung Health is retained in a versioned canonical envelope, while separate typed projections expose the latest known state for SQL and Grafana. This costs additional storage and projection logic but preserves provenance, permits future reprocessing when mappings evolve, and reflects source changes or removals without destroying ingestion history.
