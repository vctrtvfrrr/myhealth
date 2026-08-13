package br.etc.victor.myhealthbridge.samsung

import android.content.Context
import br.etc.victor.myhealthbridge.health.HealthCategory
import br.etc.victor.myhealthbridge.health.SamsungHealthAvailability
import br.etc.victor.myhealthbridge.health.SamsungHealthOutcome
import br.etc.victor.myhealthbridge.sync.HealthCapability
import br.etc.victor.myhealthbridge.sync.HealthRecordSource
import br.etc.victor.myhealthbridge.sync.ReadWindow
import br.etc.victor.myhealthbridge.sync.RecordPage
import br.etc.victor.myhealthbridge.sync.SourceRecord
import br.etc.victor.myhealthbridge.sync.SourceValue
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.data.Field
import com.samsung.android.sdk.health.data.data.HealthDataPoint
import com.samsung.android.sdk.health.data.data.entries.HeartRate
import com.samsung.android.sdk.health.data.request.DataType
import com.samsung.android.sdk.health.data.request.DataTypes
import com.samsung.android.sdk.health.data.request.LocalTimeFilter
import com.samsung.android.sdk.health.data.request.Ordering
import com.samsung.android.sdk.health.data.request.ReadDataRequest
import java.math.BigDecimal

/** The fields of a heart rate record this build reads, which a test pins to what the SDK exposes. */
internal val heartRateFields: List<Field<*>> = listOf(
    DataType.HeartRateType.HEART_RATE,
    DataType.HeartRateType.MIN_HEART_RATE,
    DataType.HeartRateType.MAX_HEART_RATE,
    DataType.HeartRateType.SERIES_DATA,
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
                records = response.dataList.map(HealthDataPoint::toSourceRecord),
                nextPageToken = response.pageToken,
            )
        }
    }

    /** Null for a capability this build declares but cannot yet build a read for. */
    private fun requestOf(
        capability: HealthCapability,
        window: ReadWindow,
        pageToken: String?,
    ): ReadDataRequest<HealthDataPoint>? = when (capability.category) {
        HealthCategory.HEART_RATE -> DataTypes.HEART_RATE.readDataRequestBuilder
            .setLocalTimeFilter(LocalTimeFilter.of(window.from, window.to, true, true))
            .setOrdering(Ordering.ASC)
            .setPageSize(capability.pageSize)
            .also { builder -> pageToken?.let(builder::setPageToken) }
            .build()

        else -> null
    }
}

private fun HealthDataPoint.toSourceRecord(): SourceRecord = SourceRecord(
    uid = uid,
    startTime = startTime,
    endTime = endTime,
    zoneOffset = zoneOffset,
    updateTime = updateTime,
    sourceAppId = dataSource?.appId,
    sourceDeviceId = dataSource?.deviceId,
    clientDataId = clientDataId,
    clientVersion = clientVersion,
    fields = heartRateFields.associateWithValues(this),
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
 */
private fun sourceValueOf(value: Any?): SourceValue? = when (value) {
    null -> null
    is Number -> SourceValue.Number(BigDecimal(value.toString()))
    is Enum<*> -> SourceValue.Text(value.name)
    is String -> SourceValue.Text(value)
    is List<*> -> SourceValue.Series(value.filterIsInstance<HeartRate>().map(HeartRate::entry))
    else -> null
}

/** The names mirror the fields of the record itself, because this is preserved as the source said it. */
private fun HeartRate.entry(): Map<String, SourceValue> = mapOf(
    "heart_rate" to SourceValue.Number(BigDecimal(heartRate.toString())),
    "min" to SourceValue.Number(BigDecimal(min.toString())),
    "max" to SourceValue.Number(BigDecimal(max.toString())),
    "start_time" to SourceValue.Text(startTime.toString()),
    "end_time" to SourceValue.Text(endTime.toString()),
)
