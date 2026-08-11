package br.etc.victor.myhealthbridge.api

import br.etc.victor.myhealthbridge.contract.BatchErrorCode
import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.IngestionResponse
import br.etc.victor.myhealthbridge.contract.ProblemDetails
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/** Liveness: the process answers, which says nothing about the database on purpose. */
fun Route.liveness() {
    get("/health") {
        call.respondText("OK")
    }
}

/**
 * Readiness: this instance may take traffic.
 *
 * Separating it from liveness is what lets an orchestrator pull a live API that lost its database out
 * of rotation instead of restarting a healthy process.
 */
fun Route.readiness(dataSource: DataSource) {
    get("/ready") {
        if (withContext(Dispatchers.IO) { isReady(dataSource) }) call.respondText("READY")
        else call.respondText("NOT_READY", status = HttpStatusCode.ServiceUnavailable)
    }
}

fun Route.ingestion(endpoint: IngestionEndpoint) {
    post("/ingestions") {
        endpoint.handle(call)
    }
}

/**
 * The ingestion endpoint: authenticate, read a bounded batch, validate every item on its own, then
 * persist all of it or none of it.
 */
class IngestionEndpoint(
    private val devices: IngestionDevices,
    private val store: IngestionStore,
    private val config: IngestionConfig,
) {

    suspend fun handle(call: ApplicationCall) {
        val startedAt = System.nanoTime()

        val request = try {
            call.read()
        } catch (failure: Exception) {
            IngestionLog.unavailable(failure)
            return call.refuse(BatchErrorCode.INGESTION_TEMPORARILY_UNAVAILABLE)
        }

        when (request) {
            is BatchRequest.Refused -> call.refuse(request.error)
            is BatchRequest.Accepted -> {
                val validator = ItemValidator(request.recordType)
                val validated = request.items.map(validator::validate)

                val response = persist(request.deviceId, request.contractVersion, validated)
                    ?: return call.refuse(BatchErrorCode.INGESTION_TEMPORARILY_UNAVAILABLE)

                IngestionLog.ingested(response, request.contractVersion, elapsedMs(startedAt))
                call.respondText(
                    IngestionContract.json.encodeToString(IngestionResponse.serializer(), response),
                    ContentType.Application.Json,
                )
            }
        }
    }

    /**
     * Authentication comes before any semantic parsing, so an unprovisioned caller is turned away
     * before this service spends anything on what it sent.
     */
    private suspend fun ApplicationCall.read(): BatchRequest {
        val token = bearerToken() ?: return BatchRequest.Refused(BatchErrorCode.INVALID_DEVICE_TOKEN)
        val deviceId = withContext(Dispatchers.IO) { devices.authenticate(token) }
            ?: return BatchRequest.Refused(BatchErrorCode.INVALID_DEVICE_TOKEN)

        if (!carriesUncompressedJson()) return BatchRequest.Refused(BatchErrorCode.UNSUPPORTED_MEDIA_TYPE)

        val bytes = readBounded(config.maxBytes) ?: return BatchRequest.Refused(BatchErrorCode.BATCH_TOO_LARGE)
        if (bytes.isEmpty()) return BatchRequest.Refused(BatchErrorCode.INVALID_REQUEST)

        // Strict UTF-8: replacing a malformed sequence would store a repaired identifier or payload
        // that nobody sent, and would collapse two distinct byte sequences into one record.
        val root = runCatching {
            IngestionContract.json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
        }.getOrNull() as? JsonObject
            ?: return BatchRequest.Refused(BatchErrorCode.INVALID_REQUEST)

        val items = root["items"] as? JsonArray ?: return BatchRequest.Refused(BatchErrorCode.INVALID_REQUEST)
        if (items.size > config.maxItems) return BatchRequest.Refused(BatchErrorCode.TOO_MANY_ITEMS)

        val contractVersion = (root["contractVersion"] as? JsonPrimitive)
            ?.takeIf { !it.isString }
            ?.content
            ?.toIntOrNull()
        val recordType = (root["recordType"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        // An empty batch carries no observation, so accepting it would only record an unusable ingestion.
        if (contractVersion != IngestionContract.CURRENT_VERSION || recordType.isNullOrEmpty() || items.isEmpty()) {
            return BatchRequest.Refused(BatchErrorCode.INVALID_BATCH)
        }

        return BatchRequest.Accepted(deviceId, contractVersion, recordType, items)
    }

    /**
     * The deadline lives in the store, with the transaction it has to bound. Cancelling this coroutine
     * would not stop a blocking driver, and would let a cancelled ingestion commit anyway.
     */
    private suspend fun persist(
        deviceId: Long,
        contractVersion: Int,
        validated: List<ItemValidation>,
    ): IngestionResponse? = try {
        withContext(Dispatchers.IO) { store.persist(deviceId, contractVersion, Instant.now(), validated) }
    } catch (failure: Exception) {
        IngestionLog.unavailable(failure)
        null
    }

    private sealed interface BatchRequest {
        data class Refused(val error: BatchErrorCode) : BatchRequest

        data class Accepted(
            val deviceId: Long,
            val contractVersion: Int,
            val recordType: String,
            val items: List<JsonElement>,
        ) : BatchRequest
    }
}

private fun ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]
        ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
        ?.substring(BEARER_PREFIX.length)
        ?.takeIf { it.isNotEmpty() }

private fun ApplicationCall.carriesUncompressedJson(): Boolean {
    val encoding = request.headers[HttpHeaders.ContentEncoding]
    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) return false
    // Equality, not a match: a request that declares no type must not pass as `*/*`.
    return runCatching { request.contentType().withoutParameters() }.getOrNull() == ContentType.Application.Json
}

/** Returns null as soon as the body passes the configured limit, so the excess is never buffered. */
private suspend fun ApplicationCall.readBounded(maxBytes: Int): ByteArray? {
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > maxBytes) return null

    val stream = receiveStream()
    return withContext(Dispatchers.IO) {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var read = stream.read(chunk)
        while (read >= 0) {
            if (buffer.size() + read > maxBytes) return@withContext null
            buffer.write(chunk, 0, read)
            read = stream.read(chunk)
        }
        buffer.toByteArray()
    }
}

private suspend fun ApplicationCall.refuse(error: BatchErrorCode) {
    IngestionLog.refused(error)
    respondText(
        text = IngestionContract.json.encodeToString(ProblemDetails.serializer(), ProblemDetails.of(error)),
        contentType = ContentType.parse(ProblemDetails.MEDIA_TYPE),
        status = HttpStatusCode.fromValue(error.status),
    )
}

private fun elapsedMs(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

private const val BEARER_PREFIX = "Bearer "
private const val READ_CHUNK_BYTES = 8 * 1024
