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

**Health Category**:
A cataloged Samsung Health capability that the system can access independently, normally corresponding to a readable data type and its required read permission; special capabilities such as profile and associated exercise location remain distinct categories.
_Avoid_: Data group, screen section

**Samsung Health Availability**:
The currently observed ability of the Samsung Health platform to serve the application's operations, including whether remediation or platform support is required; it is not a persistent session.
_Avoid_: Samsung Health connection, connected state

**Permission State**:
The application's current understanding of read access for a Health Category: not requested, granted, denied, or revoked. Denied records an unsuccessful request, while revoked means access was previously observed as granted and later became absent.
_Avoid_: SDK permission status, consent status

## Boundaries

**Personal Wellness Tracking**:
The use of the Personal Health History for observation, exploration, and visualization without diagnosis, treatment, or clinical alerting.
_Avoid_: Health monitoring, medical monitoring
