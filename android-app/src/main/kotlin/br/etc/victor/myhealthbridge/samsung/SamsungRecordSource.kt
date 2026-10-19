package br.etc.victor.myhealthbridge.samsung

import android.content.Context
import br.etc.victor.myhealthbridge.contract.SourceScope
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import br.etc.victor.myhealthbridge.sync.ActivitySummaryMapper
import br.etc.victor.myhealthbridge.sync.AggregateWindow
import br.etc.victor.myhealthbridge.sync.ChangePage
import br.etc.victor.myhealthbridge.sync.ChangeWindow
import br.etc.victor.myhealthbridge.sync.ProfileCapability
import br.etc.victor.myhealthbridge.sync.RecordCapability
import br.etc.victor.myhealthbridge.sync.HealthRecordSource
import br.etc.victor.myhealthbridge.sync.ReadWindow
import br.etc.victor.myhealthbridge.sync.RecordPage
import br.etc.victor.myhealthbridge.sync.SleepGoalMapper
import br.etc.victor.myhealthbridge.sync.SnapshotCapability
import br.etc.victor.myhealthbridge.sync.SnapshotWindowUnit
import br.etc.victor.myhealthbridge.sync.SourceAggregate
import br.etc.victor.myhealthbridge.sync.SourceChange
import br.etc.victor.myhealthbridge.sync.SourceRecord
import br.etc.victor.myhealthbridge.sync.SourceValue
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.AggregateOperation
import com.samsung.android.sdk.health.data.data.Change
import com.samsung.android.sdk.health.data.data.ChangeType
import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.entries.ExerciseLocation
import com.samsung.android.sdk.health.data.data.entries.ExerciseLog
import com.samsung.android.sdk.health.data.data.entries.ExerciseSession
import com.samsung.android.sdk.health.data.data.entries.HeartRate
import com.samsung.android.sdk.health.data.data.entries.SleepSession
import com.samsung.android.sdk.health.data.data.entries.SwimmingLog
import com.samsung.android.sdk.health.data.request.AggregateRequest
import com.samsung.android.sdk.health.data.request.AggregateSourceFilter
import com.samsung.android.sdk.health.data.request.ChangedDataRequest
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.InstantTimeFilter
import com.samsung.android.sdk.health.data.request.LocalDateFilter
import com.samsung.android.sdk.health.data.request.LocalDateGroup
import com.samsung.android.sdk.health.data.request.LocalDateGroupUnit
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.LocalTimeGroup
import com.samsung.android.sdk.health.data.request.LocalTimeGroupUnit
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

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

/** One total Samsung Health aggregates over a window, handed over under the [name] its mapper reads. */
internal class AggregateTotal<T : Any>(
    val name: String,
    val operation: AggregateOperation<T, AggregateRequest.LocalTimeBuilder<T>>,
)

/** The totals of the activity summary this build reads, which a test pins to what the SDK exposes. */
internal val activitySummaryTotals: List<AggregateTotal<*>> = listOf(
    AggregateTotal(ActivitySummaryMapper.TOTAL_ACTIVE_CALORIES_BURNED, DataType.ActivitySummaryType.TOTAL_ACTIVE_CALORIES_BURNED),
    AggregateTotal(ActivitySummaryMapper.TOTAL_CALORIES_BURNED, DataType.ActivitySummaryType.TOTAL_CALORIES_BURNED),
    AggregateTotal(ActivitySummaryMapper.TOTAL_ACTIVE_TIME, DataType.ActivitySummaryType.TOTAL_ACTIVE_TIME),
    AggregateTotal(ActivitySummaryMapper.TOTAL_DISTANCE, DataType.ActivitySummaryType.TOTAL_DISTANCE),
)

/** The fields of a sleep record this build reads, pinned to the SDK the same way. */
internal val sleepFields: List<Field<*>> = listOf(
    DataType.SleepType.DURATION,
    DataType.SleepType.SLEEP_SCORE,
    DataType.SleepType.SESSIONS,
)

/** The fields of the profile this build reads, pinned to the SDK the same way. */
internal val userProfileFields: List<Field<*>> = listOf(
    DataType.UserProfileDataType.DATE_OF_BIRTH,
    DataType.UserProfileDataType.HEIGHT,
    DataType.UserProfileDataType.WEIGHT,
    DataType.UserProfileDataType.GENDER,
    DataType.UserProfileDataType.NICKNAME,
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
        capability: RecordCapability,
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
        capability: RecordCapability,
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

    /**
     * Collects every page of the aggregate before handing it on, because the importer observes a window
     * Samsung Health left out as empty: a page not read yet would pass for a stretch of empty hours.
     */
    override suspend fun readAggregates(
        capability: SnapshotCapability,
        window: AggregateWindow,
    ): SamsungHealthOutcome<List<SourceAggregate>> = when (capability.category) {
        HealthCategory.STEPS -> outcomes.observing {
            aggregate(capability, window) { DataType.StepsType.TOTAL.requestBuilder }
        }
        HealthCategory.ACTIVITY_SUMMARY -> outcomes.observing {
            together(activitySummaryTotals.map { total -> total.name to aggregate(capability, window, total) })
        }
        HealthCategory.ACTIVE_CALORIES_BURNED_GOAL -> outcomes.observing {
            dailyGoal(window) { DataType.ActiveCaloriesBurnedGoalType.LAST.requestBuilder }
        }
        HealthCategory.ACTIVE_TIME_GOAL -> outcomes.observing {
            dailyGoal(window) { DataType.ActiveTimeGoalType.LAST.requestBuilder }
        }
        HealthCategory.NUTRITION_GOAL -> outcomes.observing {
            dailyGoal(window) { DataType.NutritionGoalType.LAST_CALORIES.requestBuilder }
        }
        HealthCategory.SLEEP_GOAL -> outcomes.observing { sleepGoal(window) }
        HealthCategory.STEPS_GOAL -> outcomes.observing {
            dailyGoal(window) { DataType.StepsGoalType.LAST.requestBuilder }
        }
        HealthCategory.WATER_INTAKE_GOAL -> outcomes.observing {
            dailyGoal(window) { DataType.WaterIntakeGoalType.LAST.requestBuilder }
        }
        else -> SamsungHealthOutcome.Failed(
            SamsungHealthAvailability.Unsupported("uncatalogued_aggregate_${capability.snapshotType}"),
        )
    }

    private suspend fun <T : Any> aggregate(
        capability: SnapshotCapability,
        window: AggregateWindow,
        total: AggregateTotal<T>,
    ): List<SourceAggregate> = aggregate(capability, window) { total.operation.requestBuilder }

    /**
     * One aggregate per window holding every total read for it, under its name. A window none of the
     * totals reported is left out, as the importer expects of a window with nothing in it.
     */
    private fun together(totals: List<Pair<String, List<SourceAggregate>>>): List<SourceAggregate> =
        totals
            .flatMap { (name, aggregates) -> aggregates.mapNotNull { a -> a.value?.let { a.start to (name to it) } } }
            .groupBy({ it.first }, { it.second })
            .toSortedMap()
            .map { (start, values) -> SourceAggregate(start, SourceValue.Nested(values.toMap())) }

    /** The profile is read whole: its request takes no filter, no page and no ordering. */
    override suspend fun readProfile(capability: ProfileCapability): SamsungHealthOutcome<Map<String, SourceValue>?> =
        outcomes.observing {
            store.readData(DataTypes.USER_PROFILE.readDataRequestBuilder.build())
                .dataList
                .firstOrNull()
                ?.let { profile ->
                    buildMap {
                        userProfileFields.forEach { field ->
                            sourceValueOf(profile.getValue(field))?.let { put(field.name, it) }
                        }
                    }
                }
        }

    /**
     * Collects every page of the aggregate before handing it on, because the importer observes a window
     * Samsung Health left out as empty: a page not read yet would pass for a stretch of empty hours.
     */
    private suspend fun <T : Any> aggregate(
        capability: SnapshotCapability,
        window: AggregateWindow,
        builder: () -> AggregateRequest.LocalTimeBuilder<T>,
    ): List<SourceAggregate> = buildList {
        var pageToken: String? = null
        do {
            val request = builder()
                .setLocalTimeFilterWithGroup(
                    LocalTimeFilter.of(window.from, window.until, true, false),
                    LocalTimeGroup.of(groupUnitOf(capability.window), 1),
                )
                .apply {
                    if (capability.mapper.sourceScope == SourceScope.PLATFORM) {
                        setSourceFilter(AggregateSourceFilter.fromPlatform())
                    }
                }
                .setOrdering(Ordering.ASC)
                .also { request -> pageToken?.let(request::setPageToken) }
                .build()
            val response = store.aggregateData(request)
            response.dataList.forEach { add(SourceAggregate(it.getStartLocalDateTime(), sourceValueOf(it.value))) }
            pageToken = response.pageToken
        } while (pageToken != null)
    }

    /**
     * The goal in force on each local day of [window], which Samsung Health only aggregates by local date
     * and over every source. Every page is collected for the same reason as [aggregate].
     */
    private suspend fun <T : Any> dailyGoal(
        window: AggregateWindow,
        builder: () -> AggregateRequest.AllSourceLocalDateBuilder<T>,
    ): List<SourceAggregate> = buildList {
        var pageToken: String? = null
        do {
            val request = builder()
                .setLocalDateFilterWithGroup(
                    LocalDateFilter.of(window.from.toLocalDate(), window.until.toLocalDate(), true, false),
                    LocalDateGroup.of(LocalDateGroupUnit.DAILY, 1),
                )
                .setOrdering(Ordering.ASC)
                .also { request -> pageToken?.let(request::setPageToken) }
                .build()
            val response = store.aggregateData(request)
            response.dataList.forEach {
                add(SourceAggregate(it.getStartLocalDateTime().toLocalDate().atStartOfDay(), sourceValueOf(it.value)))
            }
            pageToken = response.pageToken
        } while (pageToken != null)
    }

    /** The bed and wake up times Samsung Health aggregates apart, joined into the goal of each day. */
    private suspend fun sleepGoal(window: AggregateWindow): List<SourceAggregate> {
        val bedTimes = dailyGoal(window) { DataType.SleepGoalType.LAST_BED_TIME.requestBuilder }
        val wakeUpTimes = dailyGoal(window) { DataType.SleepGoalType.LAST_WAKE_UP_TIME.requestBuilder }
        val bedTime = bedTimes.associate { it.start to it.value }
        val wakeUpTime = wakeUpTimes.associate { it.start to it.value }
        return (bedTime.keys + wakeUpTime.keys).sorted().map { day ->
            val goal = listOfNotNull(
                bedTime[day]?.let { SleepGoalMapper.BED_TIME to it },
                wakeUpTime[day]?.let { SleepGoalMapper.WAKE_UP_TIME to it },
            ).toMap()
            SourceAggregate(day, goal.takeIf { it.isNotEmpty() }?.let(SourceValue::Nested))
        }
    }

    /** Null for a capability this build declares but cannot yet build a read for. */
    private fun requestOf(
        capability: RecordCapability,
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
        capability: RecordCapability,
        window: ChangeWindow,
        pageToken: String?,
    ): ChangedDataRequest<HealthDataPoint>? = changesBuilderOf(capability)
        ?.setChangeTimeFilter(InstantTimeFilter.of(window.from, window.to, true, true))
        ?.setPageSize(capability.pageSize)
        ?.also { builder -> pageToken?.let(builder::setPageToken) }
        ?.build()

    private fun readBuilderOf(
        capability: RecordCapability,
    ): ReadDataRequest.DualTimeBuilder<HealthDataPoint>? = when (capability.category) {
        HealthCategory.HEART_RATE -> DataTypes.HEART_RATE.readDataRequestBuilder
        HealthCategory.EXERCISE -> DataTypes.EXERCISE.readDataRequestBuilder
        HealthCategory.SLEEP -> DataTypes.SLEEP.readDataRequestBuilder
        else -> null
    }

    private fun groupUnitOf(unit: SnapshotWindowUnit): LocalTimeGroupUnit = when (unit) {
        SnapshotWindowUnit.LOCAL_HOUR -> LocalTimeGroupUnit.HOURLY
        SnapshotWindowUnit.LOCAL_DAY -> LocalTimeGroupUnit.DAILY
    }

    private fun changesBuilderOf(
        capability: RecordCapability,
    ): ChangedDataRequest.BasicBuilder<HealthDataPoint>? = when (capability.category) {
        HealthCategory.HEART_RATE -> DataTypes.HEART_RATE.changedDataRequestBuilder
        HealthCategory.EXERCISE -> DataTypes.EXERCISE.changedDataRequestBuilder
        HealthCategory.SLEEP -> DataTypes.SLEEP.changedDataRequestBuilder
        else -> null
    }
}

/** The fields this build reads out of a record, or none for a category it does not read. */
private fun fieldsOf(capability: RecordCapability): List<Field<*>> = when (capability.category) {
    HealthCategory.HEART_RATE -> heartRateFields
    HealthCategory.EXERCISE -> exerciseFields
    HealthCategory.SLEEP -> sleepFields
    else -> emptyList()
}

/**
 * A removal that does not name what was removed is dropped rather than guessed at, the way an unknown
 * value type is: it cannot happen without an SDK change.
 */
private fun Change<HealthDataPoint>.toSourceChange(capability: RecordCapability): SourceChange? = when (changeType) {
    ChangeType.UPSERT -> SourceChange.Upserted(changeTime, upsertDataPoint.toSourceRecord(capability))
    ChangeType.DELETE -> deleteDataUid?.let { SourceChange.Removed(changeTime, it) }
}

private fun HealthDataPoint.toSourceRecord(capability: RecordCapability): SourceRecord = SourceRecord(
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
    is LocalTime -> SourceValue.Text(value.toString())
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
    is SleepSession -> sleepSessionEntries.entryOf(element)
    is SleepSession.SleepStage -> sleepStageEntries.entryOf(element)
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

internal val sleepSessionEntries: Entries<SleepSession> = listOf(
    "start_time" to SleepSession::startTime,
    "end_time" to SleepSession::endTime,
    "duration" to SleepSession::duration,
    "stages" to SleepSession::stages,
)

internal val sleepStageEntries: Entries<SleepSession.SleepStage> = listOf(
    "start_time" to SleepSession.SleepStage::startTime,
    "end_time" to SleepSession.SleepStage::endTime,
    "stage" to SleepSession.SleepStage::stage,
)
