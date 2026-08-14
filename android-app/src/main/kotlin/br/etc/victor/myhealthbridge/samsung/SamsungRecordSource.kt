package br.etc.victor.myhealthbridge.samsung

import android.content.Context
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import br.etc.victor.myhealthbridge.sync.ChangePage
import br.etc.victor.myhealthbridge.sync.ChangeWindow
import br.etc.victor.myhealthbridge.sync.HealthCapability
import br.etc.victor.myhealthbridge.sync.HealthRecordSource
import br.etc.victor.myhealthbridge.sync.ReadWindow
import br.etc.victor.myhealthbridge.sync.RecordPage
import br.etc.victor.myhealthbridge.sync.SourceChange
import br.etc.victor.myhealthbridge.sync.SourceRecord
import br.etc.victor.myhealthbridge.sync.SourceValue
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.Change
import com.samsung.android.sdk.health.data.data.ChangeType
import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.entries.ExerciseLocation
import com.samsung.android.sdk.health.data.data.entries.ExerciseLog
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
import com.samsung.android.sdk.health.data.data.entries.HeartRate
import com.samsung.android.sdk.health.data.data.entries.SwimmingLog
import com.samsung.android.sdk.health.data.request.ChangedDataRequest
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** The fields of a heart rate record this build reads, which a test pins to what the SDK exposes. */
internal val heartRateFields: List<Field<*>> = listOf(
    DataType.HeartRateType.HEART_RATE,
    DataType.HeartRateType.MIN_HEART_RATE,
    DataType.HeartRateType.MAX_HEART_RATE,
    DataType.HeartRateType.SERIES_DATA,
)

/** The fields of an exercise record this build reads, pinned to the SDK the same way. */
internal val exerciseFields: List<Field<*>> = listOf(
    DataType.ExerciseType.EXERCISE_TYPE,
    DataType.ExerciseType.CUSTOM_TITLE,
    DataType.ExerciseType.SESSIONS,
)

/**
 * Reads Health Records from Samsung Health, one page at a time, and hands them on with no SDK type
 * left in them.
 *
 * Reading over local time rather than over instants is what makes a cursor resumable: the filter is
 * expressed in the same terms the stored cursor is, so a restart can ask for the rest of the window.
 */
class SamsungRecordSource(
    private val context: Context,
    foregroundActivity: () -> android.app.Activity? = { ForegroundActivity.current },
) : HealthRecordSource {

    private val store: HealthDataStore by lazy { HealthDataService.getStore(context) }

    private val outcomes = SamsungOutcomes(foregroundActivity)

    override suspend fun readPage(
        capability: HealthCapability,
        window: ReadWindow,
        pageToken: String?,
    ): SamsungHealthOutcome<RecordPage> {
        val request = requestOf(capability, window, pageToken)
            ?: return SamsungHealthOutcome.Failed(
                SamsungHealthAvailability.Unsupported("uncatalogued_read_${capability.recordType}"),
            )

        return outcomes.observing {
            val response = store.readData(request)
            RecordPage(
                records = response.dataList.map { it.toSourceRecord(capability) },
                nextPageToken = response.pageToken,
            )
        }
    }

    override suspend fun readChanges(
        capability: HealthCapability,
        window: ChangeWindow,
        pageToken: String?,
    ): SamsungHealthOutcome<ChangePage> {
        val request = changesRequestOf(capability, window, pageToken)
            ?: return SamsungHealthOutcome.Failed(
                SamsungHealthAvailability.Unsupported("uncatalogued_changes_${capability.recordType}"),
            )

        return outcomes.observing {
            val response = store.readChanges(request)
            ChangePage(
                changes = response.dataList.mapNotNull { it.toSourceChange(capability) },
                nextPageToken = response.pageToken,
            )
        }
    }

    /** Null for a capability this build declares but cannot yet build a read for. */
    private fun requestOf(
        capability: HealthCapability,
        window: ReadWindow,
        pageToken: String?,
    ): ReadDataRequest<HealthDataPoint>? = readBuilderOf(capability)
        ?.setLocalTimeFilter(LocalTimeFilter.of(window.from, window.to, true, true))
        ?.setOrdering(Ordering.ASC)
        ?.setPageSize(capability.pageSize)
        ?.also { builder -> pageToken?.let(builder::setPageToken) }
        ?.build()

    /** Null for a capability that declares the changes feed without this build reading it. */
    private fun changesRequestOf(
        capability: HealthCapability,
        window: ChangeWindow,
        pageToken: String?,
    ): ChangedDataRequest<HealthDataPoint>? = changesBuilderOf(capability)
        ?.setChangeTimeFilter(InstantTimeFilter.of(window.from, window.to, true, true))
        ?.setPageSize(capability.pageSize)
        ?.also { builder -> pageToken?.let(builder::setPageToken) }
        ?.build()

    private fun readBuilderOf(
        capability: HealthCapability,
    ): ReadDataRequest.DualTimeBuilder<HealthDataPoint>? = when (capability.category) {
        HealthCategory.HEART_RATE -> DataTypes.HEART_RATE.readDataRequestBuilder
        HealthCategory.EXERCISE -> DataTypes.EXERCISE.readDataRequestBuilder
        else -> null
    }

    private fun changesBuilderOf(
        capability: HealthCapability,
    ): ChangedDataRequest.BasicBuilder<HealthDataPoint>? = when (capability.category) {
        HealthCategory.HEART_RATE -> DataTypes.HEART_RATE.changedDataRequestBuilder
        HealthCategory.EXERCISE -> DataTypes.EXERCISE.changedDataRequestBuilder
        else -> null
    }
}

/** The fields this build reads out of a record, or none for a category it does not read. */
private fun fieldsOf(capability: HealthCapability): List<Field<*>> = when (capability.category) {
    HealthCategory.HEART_RATE -> heartRateFields
    HealthCategory.EXERCISE -> exerciseFields
    else -> emptyList()
}

/**
 * A removal that does not name what was removed is dropped rather than guessed at, the way an unknown
 * value type is: it cannot happen without an SDK change.
 */
private fun Change<HealthDataPoint>.toSourceChange(capability: HealthCapability): SourceChange? = when (changeType) {
    ChangeType.UPSERT -> SourceChange.Upserted(changeTime, upsertDataPoint.toSourceRecord(capability))
    ChangeType.DELETE -> deleteDataUid?.let { SourceChange.Removed(changeTime, it) }
}

private fun HealthDataPoint.toSourceRecord(capability: HealthCapability): SourceRecord = SourceRecord(
    uid = uid,
    startTime = startTime,
    endTime = endTime,
    zoneOffset = zoneOffset,
    updateTime = updateTime,
    sourceAppId = dataSource?.appId,
    sourceDeviceId = dataSource?.deviceId,
    clientDataId = clientDataId,
    clientVersion = clientVersion,
    fields = fieldsOf(capability).associateWithValues(this),
)

private fun List<Field<*>>.associateWithValues(point: HealthDataPoint): Map<String, SourceValue> =
    buildMap {
        this@associateWithValues.forEach { field ->
            sourceValueOf(point.getValue(field))?.let { put(field.name, it) }
        }
    }

/**
 * A value type this build does not know is dropped rather than guessed at. It cannot happen without
 * an SDK change, which the field coverage test refuses before it can reach a device.
 *
 * A duration is preserved in seconds with the millisecond precision the SDK reports, so that what the
 * source stated survives without a text a reader would have to parse to compare.
 */
private fun sourceValueOf(value: Any?): SourceValue? = when (value) {
    null -> null
    is Number -> SourceValue.Number(BigDecimal(value.toString()))
    is Boolean -> SourceValue.Flag(value)
    is Enum<*> -> SourceValue.Text(value.name)
    is String -> SourceValue.Text(value)
    is Instant -> SourceValue.Text(value.toString())
    is Duration -> SourceValue.Number(BigDecimal.valueOf(value.toMillis(), 3))
    is SwimmingLog -> SourceValue.Nested(swimmingLogEntries.entryOf(value))
    is List<*> -> SourceValue.Series(value.mapNotNull(::entryOf))
    else -> null
}

/** One nested entry of a list, or null for an element type this build does not read. */
private fun entryOf(element: Any?): Map<String, SourceValue>? = when (element) {
    is HeartRate -> heartRateEntries.entryOf(element)
    is ExerciseSession -> exerciseSessionEntries.entryOf(element)
    is ExerciseLocation -> exerciseLocationEntries.entryOf(element)
    is ExerciseLog -> exerciseLogEntries.entryOf(element)
    is SwimmingLog.SwimmingInterval -> swimmingIntervalEntries.entryOf(element)
    else -> null
}

/**
 * How one nested SDK type is read, as the name each of its values is preserved under.
 *
 * The names mirror the source's own, because this is preserved as the source said it. Declaring them
 * beside the accessor is what lets a test compare the set against what the pinned SDK exposes, the way
 * a record's own fields are compared against its data type.
 */
internal typealias Entries<T> = List<Pair<String, (T) -> Any?>>

private fun <T> Entries<T>.entryOf(source: T): Map<String, SourceValue> = buildMap {
    this@entryOf.forEach { (name, read) -> sourceValueOf(read(source))?.let { put(name, it) } }
}

internal val heartRateEntries: Entries<HeartRate> = listOf(
    "heart_rate" to HeartRate::heartRate,
    "min" to HeartRate::min,
    "max" to HeartRate::max,
    "start_time" to HeartRate::startTime,
    "end_time" to HeartRate::endTime,
)

internal val exerciseSessionEntries: Entries<ExerciseSession> = listOf(
    "start_time" to ExerciseSession::startTime,
    "end_time" to ExerciseSession::endTime,
    "duration" to ExerciseSession::duration,
    "exercise_type" to ExerciseSession::exerciseType,
    "custom_title" to ExerciseSession::customTitle,
    "count_type" to ExerciseSession::countType,
    "count" to ExerciseSession::count,
    "calories" to ExerciseSession::calories,
    "distance" to ExerciseSession::distance,
    "incline_distance" to ExerciseSession::inclineDistance,
    "decline_distance" to ExerciseSession::declineDistance,
    "altitude_gain" to ExerciseSession::altitudeGain,
    "altitude_loss" to ExerciseSession::altitudeLoss,
    "max_altitude" to ExerciseSession::maxAltitude,
    "min_altitude" to ExerciseSession::minAltitude,
    "max_speed" to ExerciseSession::maxSpeed,
    "mean_speed" to ExerciseSession::meanSpeed,
    "max_cadence" to ExerciseSession::maxCadence,
    "mean_cadence" to ExerciseSession::meanCadence,
    "max_calorie_burn_rate" to ExerciseSession::maxCalorieBurnRate,
    "mean_calorie_burn_rate" to ExerciseSession::meanCalorieBurnRate,
    "max_heart_rate" to ExerciseSession::maxHeartRate,
    "mean_heart_rate" to ExerciseSession::meanHeartRate,
    "min_heart_rate" to ExerciseSession::minHeartRate,
    "max_power" to ExerciseSession::maxPower,
    "mean_power" to ExerciseSession::meanPower,
    "max_rpm" to ExerciseSession::maxRpm,
    "mean_rpm" to ExerciseSession::meanRpm,
    "vo2_max" to ExerciseSession::vo2Max,
    "auto_detected" to ExerciseSession::autoDetected,
    "comment" to ExerciseSession::comment,
    "swimming_log" to ExerciseSession::swimmingLog,
    "route" to ExerciseSession::route,
    "log" to ExerciseSession::log,
)

internal val exerciseLocationEntries: Entries<ExerciseLocation> = listOf(
    "timestamp" to ExerciseLocation::timestamp,
    "latitude" to ExerciseLocation::latitude,
    "longitude" to ExerciseLocation::longitude,
    "altitude" to ExerciseLocation::altitude,
    "accuracy" to ExerciseLocation::accuracy,
)

internal val exerciseLogEntries: Entries<ExerciseLog> = listOf(
    "timestamp" to ExerciseLog::timestamp,
    "heart_rate" to ExerciseLog::heartRate,
    "cadence" to ExerciseLog::cadence,
    "count" to ExerciseLog::count,
    "power" to ExerciseLog::power,
    "speed" to ExerciseLog::speed,
)

internal val swimmingLogEntries: Entries<SwimmingLog> = listOf(
    "pool_length" to SwimmingLog::poolLength,
    "pool_length_unit" to SwimmingLog::poolLengthUnit,
    "total_distance" to SwimmingLog::totalDistance,
    "total_duration" to SwimmingLog::totalDuration,
    "swimming_intervals" to SwimmingLog::swimmingIntervals,
)

internal val swimmingIntervalEntries: Entries<SwimmingLog.SwimmingInterval> = listOf(
    "duration" to SwimmingLog.SwimmingInterval::duration,
    "stroke_type" to SwimmingLog.SwimmingInterval::strokeType,
    "stroke_count" to SwimmingLog.SwimmingInterval::strokeCount,
    "interval" to SwimmingLog.SwimmingInterval::interval,
)
