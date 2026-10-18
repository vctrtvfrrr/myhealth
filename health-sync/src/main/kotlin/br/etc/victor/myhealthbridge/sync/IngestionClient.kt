package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.BatchErrorCode
import br.etc.victor.myhealthbridge.contract.IngestionBatch
import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.IngestionResponse
import br.etc.victor.myhealthbridge.contract.InventoryResult
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ProblemDetails
import br.etc.victor.myhealthbridge.contract.SnapshotBatch
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

sealed interface SendOutcome {

    /**
     * The API evaluated the batch and answered one result per submitted position, and what it concluded
     * from the inventory when the batch declared one.
     */
    data class Delivered(val results: List<ItemResult>, val inventory: InventoryResult? = null) : SendOutcome

    /** The API refused the whole request, and said why with a stable code. */
    data class Refused(val error: BatchErrorCode) : SendOutcome

    /** Nothing was learned about the batch: it may or may not have been stored. */
    data object Unreachable : SendOutcome
}

interface IngestionClient {

    suspend fun send(endpoint: IngestionEndpoint, batch: IngestionBatch): SendOutcome

    suspend fun sendSnapshots(endpoint: IngestionEndpoint, batch: SnapshotBatch): SendOutcome
}

/**
 * Where the client says that the version it sends has fallen behind the one the API recommends.
 *
 * It is told only the two versions, never the batch or the endpoint, so nothing biometric and no token
 * can reach the log through it.
 */
fun interface RecommendedVersionLog {

    fun behind(declaredVersion: Int, recommendedVersion: Int)
}

object LogcatRecommendedVersionLog : RecommendedVersionLog {

    override fun behind(declaredVersion: Int, recommendedVersion: Int) {
        Log.w(
            "IngestionContract",
            "Ingestion Contract Version $declaredVersion is below the recommended version $recommendedVersion",
        )
    }
}

/**
 * The ingestion client over plain HTTP.
 *
 * An answer that cannot be read as either a result list or a problem document is [SendOutcome.Unreachable]
 * on purpose: the batch may well be stored, and inventing a refusal would let the outbox be cleared
 * over something the API never said.
 *
 * Falling behind the recommended version is advice, not a Contract Incompatibility: the batch was
 * accepted, so it is only logged and never changes the outcome. See ADR 0007.
 */
class HttpIngestionClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
    private val recommendedVersionLog: RecommendedVersionLog = LogcatRecommendedVersionLog,
) : IngestionClient {

    override suspend fun send(endpoint: IngestionEndpoint, batch: IngestionBatch): SendOutcome =
        post(endpoint, IngestionContract.json.encodeToString(IngestionBatch.serializer(), batch), batch.contractVersion)

    override suspend fun sendSnapshots(endpoint: IngestionEndpoint, batch: SnapshotBatch): SendOutcome =
        post(endpoint, IngestionContract.json.encodeToString(SnapshotBatch.serializer(), batch), batch.contractVersion)

    private suspend fun post(endpoint: IngestionEndpoint, document: String, contractVersion: Int): SendOutcome =
        withContext(Dispatchers.IO) {
            val body = document.toByteArray()

            val connection = try {
                open(endpoint, body.size)
            } catch (failure: Exception) {
                return@withContext SendOutcome.Unreachable
            }

            try {
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                noticeRecommendation(contractVersion, connection)
                val answer = (if (status < 400) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes().decodeToString() }
                    .orEmpty()
                read(status, answer)
            } catch (failure: Exception) {
                SendOutcome.Unreachable
            } finally {
                connection.disconnect()
            }
        }

    private fun open(endpoint: IngestionEndpoint, contentLength: Int): HttpURLConnection {
        val url = URI(endpoint.baseUrl.trimEnd('/') + "/ingestions").toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setFixedLengthStreamingMode(contentLength)
            setRequestProperty("Authorization", "Bearer ${endpoint.deviceToken}")
            setRequestProperty("Content-Type", IngestionContract.MEDIA_TYPE)
            setRequestProperty("Accept", IngestionContract.MEDIA_TYPE)
        }
    }

    private fun noticeRecommendation(declaredVersion: Int, connection: HttpURLConnection) {
        val recommendedVersion = connection.getHeaderField(IngestionContract.RECOMMENDED_HEADER)
            ?.trim()
            ?.toIntOrNull()
            ?: return
        if (declaredVersion < recommendedVersion) {
            recommendedVersionLog.behind(declaredVersion, recommendedVersion)
        }
    }

    private fun read(status: Int, answer: String): SendOutcome {
        if (status == HttpURLConnection.HTTP_OK) {
            val response = runCatching {
                IngestionContract.json.decodeFromString(IngestionResponse.serializer(), answer)
            }.getOrNull() ?: return SendOutcome.Unreachable
            return SendOutcome.Delivered(response.results, response.inventory)
        }

        val problem = runCatching {
            IngestionContract.json.decodeFromString(ProblemDetails.serializer(), answer)
        }.getOrNull() ?: return SendOutcome.Unreachable

        val error = BatchErrorCode.entries.firstOrNull { it.wireValue == problem.code }
            ?: return SendOutcome.Unreachable
        return SendOutcome.Refused(error)
    }
}
