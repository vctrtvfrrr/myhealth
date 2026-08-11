# Personal Health Data

This context covers the personal health history made available by Samsung Health and retained for the owner's independent use outside that platform.

## Language

**Consolidated Health Data**:
Health and fitness records made available by Samsung Health, regardless of whether they originated from the Galaxy Watch, the Android phone, manual entry, or another connected device.
_Avoid_: Watch data, sensor data

**Health Record**:
A dated measurement, summary, or activity obtained from Consolidated Health Data, together with the available provenance needed to interpret its origin.
_Avoid_: Sample, event, raw data

**Personal Health History**:
The durable collection of Health Records owned and controlled by the sole user of the system.
_Avoid_: Samsung Health history, local history

**Observed Record Version**:
The faithful representation of a Health Record as it was seen during a particular import, retained even if a later import reports different content or removal.
_Avoid_: Backup copy, raw record

**Current Health Record**:
The latest known state of a Health Record, derived from its observed versions and used for ordinary queries and visualizations.
_Avoid_: Latest sample, live record

**Observed Snapshot**:
A value, aggregate, goal, or profile state captured at a known observation time when Samsung Health does not expose it as an individually identifiable historical record.
_Avoid_: Health Record, historical measurement

**Source Removal**:
The observed absence or deletion of a previously imported Health Record in Samsung Health; it changes the current state without erasing the record's ingestion history.
_Avoid_: Hard delete, purge

**Data Owner**:
The sole person whose Consolidated Health Data is collected and who uses the system for personal wellness tracking.
_Avoid_: Customer, patient, app user

## Boundaries

**Personal Wellness Tracking**:
The use of the Personal Health History for observation, exploration, and visualization without diagnosis, treatment, or clinical alerting.
_Avoid_: Health monitoring, medical monitoring
