# Grant read access outside the migrations

The migrations create the read schema and its views, but neither create the read-only login role nor grant anything to it. Creating a role needs a privilege the runtime role of the API is not guaranteed to hold, and granting to a role that does not exist fails: either one inside a migration turns a missing database administration step into an API that refuses to start, which is the opposite of what ADR 0006 traded least privilege for.

The grants therefore live in `docs/sql/read-access.sql`, run once by the operator after the role exists, and the integration test executes that same file instead of restating it. What is documented and what is verified cannot drift when they are the same text.

Read access needs no privilege on the ingestion tables. A view runs with the privileges of the role that owns it, so the read account reaches the preserved envelopes only through the shape a view exposes, and PostgreSQL grants a new role nothing on an existing table by default. The read model being a separate schema is what makes that a rule about a schema rather than a list of tables somebody has to keep current.
