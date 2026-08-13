# Personal Health Data

This context covers the personal health history made available by Samsung Health and retained for the owner's independent use outside that platform.

## Language

**Consolidated Health Data**:
Health and fitness records made available by Samsung Health, regardless of whether they originated from the Galaxy Watch, the Android phone, manual entry, or another connected device.
_Avoid_: Watch data, sensor data

**Health Record**:
A dated measurement, summary, or activity obtained from Consolidated Health Data, together with the available provenance needed to interpret its origin.
_Avoid_: Sample, event, raw data

**Health Record Identity**:
The stable identity of a Health Record, formed by its Samsung Health record type and Samsung-assigned identifier; changes to observed content or provenance do not create a different Health Record.
_Avoid_: Version identity, ingestion identity

**Source Provenance**:
The available identity of the application and device from which Samsung Health reports a Health Record, including an explicit unknown value when the source does not provide either identity.
_Avoid_: Ingestion source, uploader identity

**Personal Health History**:
The durable collection of Health Records owned and controlled by the sole user of the system.
_Avoid_: Samsung Health history, local history

**Observed Record Version**:
The faithful canonical representation of a Health Record as it was seen during a particular import, retained even if a later import reports different content or removal. Two observations are the same version only when all preserved content, including provenance and original temporal context, is equal.
_Avoid_: Backup copy, raw record

**Current Health Record**:
The latest known state of a Health Record, derived from its observed versions and used for ordinary queries and visualizations.
_Avoid_: Latest sample, live record

**Observed Snapshot**:
A value, aggregate, goal, or profile state captured at a known observation time when Samsung Health does not expose it as an individually identifiable historical record.
_Avoid_: Health Record, historical measurement

**Source Removal**:
The observed absence or deletion of a previously imported Health Record in Samsung Health, preserved as an Observed Record Version without biometric content; it changes the current state without erasing the record's ingestion history.
_Avoid_: Hard delete, purge

**Data Owner**:
The sole person whose Consolidated Health Data is collected and who uses the system for personal wellness tracking.
_Avoid_: Customer, patient, app user

**Ingestion Device**:
A provisioned device authorized to deliver Health Records to the ingestion API, identified by a token whose digest is the only part the system retains; it is not necessarily the device the Source Provenance names.
_Avoid_: Client, API user, source device

**Ingestion**:
A single authenticated attempt to deliver a batch of Observed Record Versions, recorded with its positional result for every submitted item; a retry is a new Ingestion, never a replay of an earlier answer.
_Avoid_: Upload session, sync run, batch id

**Ingestion Contract Version**:
The version of the transport surface shared by the application and the ingestion API, declared by the application on every batch so that both sides can be released independently.
_Avoid_: API version, protocol version, schema version

**Supported Contract Range**:
The set of Ingestion Contract Versions the API accepts, bounded below by the minimum supported version and above by the maximum, which is the newest version the API itself speaks. Both bounds are published, along with a recommended version that is advice on what to send rather than a bound. The range is currently a single version, and being a range is what allows the two sides to be updated at different moments.
_Avoid_: Supported version, version list

**Contract Incompatibility**:
The condition of a well formed batch whose declared Ingestion Contract Version falls outside the Supported Contract Range. The remediation is opposite on each side of the range: below it the application must be updated, above it the API must be.
_Avoid_: Version error, unsupported request, malformed batch

**Health Category**:
A cataloged Samsung Health capability that the system can access independently, normally corresponding to a readable data type and its required read permission; special capabilities such as profile and associated exercise location remain distinct categories.
_Avoid_: Data group, screen section

**Samsung Health Availability**:
The currently observed ability of the Samsung Health platform to serve the application's operations, including whether remediation or platform support is required; it is not a persistent session.
_Avoid_: Samsung Health connection, connected state

**Permission State**:
The application's current understanding of read access for a Health Category: not requested, granted, denied, or revoked. Denied records an unsuccessful request, while revoked means access was previously observed as granted and later became absent.
_Avoid_: SDK permission status, consent status

**Health Capability**:
A cataloged declaration of how a Health Category is synchronized: the record type it becomes on the wire, the read operations it supports, the size of a read page, the mapper that renders it and whether the API projects it. A Health Category without one is cataloged for permissions and not synchronized.
_Avoid_: Data type support, sync config

**Sync Cursor**:
How far the import of one Health Category has read, expressed as the local time the next read starts at and the instant its Source Change Feed continues from, together with how its last run ended. It is independent per Health Category, so a category that cannot be read holds none of the others back.
_Avoid_: Sync state, checkpoint, offset

**Source Change Feed**:
What Samsung Health reports as changed about the Health Records of a Health Category over an interval of change time, as edits and Source Removals. It is the only read that reaches a record the import already walked past, and the only one that can report an absence. It promises no order, so it is read and resumed by whole intervals rather than by the change times inside them.
_Avoid_: Delta sync, change log, subscription

**Overlap Re-read**:
A daily re-reading of the last seven days of a Health Category, taken by pulling its Sync Cursor back before the ordinary read. It bounds how far the history can diverge from the source when neither the cursor nor the Source Change Feed reported a change.
_Avoid_: Backfill, retry window

**Full Reconciliation**:
A re-reading of the whole accessible history of every granted Health Category, asked for by the Data Owner, trusting no Sync Cursor. It recovers from lost local state or a suspected divergence, and duplicates nothing because a re-read produces the same Observed Record Versions. It cannot recover a Source Removal that was never observed, which no re-reading can.
_Avoid_: Resync, full sync, repair

**Unrecoverable Sync Cursor**:
A stored Sync Cursor whose position this build cannot interpret. It is never given a default position: the Health Category it belongs to is answered with a Full Reconciliation of itself and the condition is reported, because a guessed position is a gap nothing would ever report.
_Avoid_: Corrupt cursor, invalid state

**Outbox**:
The durable local queue of mapped envelopes on the device, holding each one only until the ingestion API confirms it is stored. It bounds how far ahead of the API the import may read.
_Avoid_: Local cache, upload queue, local database

**Mapping Pendency**:
An Outbox item the API rejected, kept on the device and taken out of every later batch. It records a mapper defect for its owner to resolve, and never blocks the progress of the records around it.
_Avoid_: Failed upload, error queue, dead letter

## Boundaries

**Personal Wellness Tracking**:
The use of the Personal Health History for observation, exploration, and visualization without diagnosis, treatment, or clinical alerting.
_Avoid_: Health monitoring, medical monitoring
