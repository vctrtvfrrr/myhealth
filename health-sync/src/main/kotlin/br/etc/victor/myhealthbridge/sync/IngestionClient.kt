package br.etc.victor.myhealthbridge.sync

import br.etc.victor.myhealthbridge.contract.BatchErrorCode
import br.etc.victor.myhealthbridge.contract.IngestionBatch
import br.etc.victor.myhealthbridge.contract.IngestionContract
import br.etc.victor.myhealthbridge.contract.IngestionResponse
import br.etc.victor.myhealthbridge.contract.ItemResult
import br.etc.victor.myhealthbridge.contract.ProblemDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

sealed interface SendOutcome {

    /** The API evaluated the batch and answered one result per submitted position. */
    data class Delivered(val results: List<ItemResult>) : SendOutcome

    /** The API refused the whole request, and said why with a stable code. */
    data class Refused(val error: BatchErrorCode) : SendOutcome

    /** Nothing was learned about the batch: it may or may not have been stored. */
    data object Unreachable : SendOutcome
}

interface IngestionClient {

    suspend fun send(endpoint: IngestionEndpoint, batch: IngestionBatch): SendOutcome
}

/**
 * The ingestion client over plain HTTP.
 *
 * An answer that cannot be read as either a result list or a problem document is [SendOutcome.Unreachable]
 * on purpose: the batch may well be stored, and inventing a refusal would let the outbox be cleared
 * over something the API never said.
 */
class HttpIngestionClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : IngestionClient {

    override suspend fun send(endpoint: IngestionEndpoint, batch: IngestionBatch): SendOutcome =
        withContext(Dispatchers.IO) {
            val body = IngestionContract.json
                .encodeToString(IngestionBatch.serializer(), batch)
                .toByteArray()

            val connection = try {
                open(endpoint, body.size)
            } catch (failure: Exception) {
                return@withContext SendOutcome.Unreachable
            }

            try {
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
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

    private fun read(status: Int, answer: String): SendOutcome {
        if (status == HttpURLConnection.HTTP_OK) {
            val response = runCatching {
                IngestionContract.json.decodeFromString(IngestionResponse.serializer(), answer)
            }.getOrNull() ?: return SendOutcome.Unreachable
            return SendOutcome.Delivered(response.results)
        }

        val problem = runCatching {
            IngestionContract.json.decodeFromString(ProblemDetails.serializer(), answer)
        }.getOrNull() ?: return SendOutcome.Unreachable

        val error = BatchErrorCode.entries.firstOrNull { it.wireValue == problem.code }
            ?: return SendOutcome.Unreachable
        return SendOutcome.Refused(error)
    }
}
