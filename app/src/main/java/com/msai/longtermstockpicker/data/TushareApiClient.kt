package com.msai.longtermstockpicker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class TushareApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) {

    suspend fun postDaily(
        token: String,
        tsCode: String,
        startDate: String,
        endDate: String,
    ): Result<TushareEnvelope> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonBody = buildDailyRequestJson(token, tsCode, startDate, endDate)
            val req = Request.Builder()
                .url("http://api.tushare.pro")
                .post(jsonBody.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: $text")
                }
                parseTushareEnvelope(text)
            }
        }
    }

    suspend fun postStockBasic(token: String): Result<TushareEnvelope> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonBody = buildStockBasicRequestJson(token)
            val req = Request.Builder()
                .url("http://api.tushare.pro")
                .post(jsonBody.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: $text")
                }
                parseTushareEnvelope(text)
            }
        }
    }

    suspend fun postFinancial(
        token: String,
        apiName: String,
        tsCode: String,
        fields: String,
    ): Result<TushareEnvelope> = withContext(Dispatchers.IO) {
        runCatching {
            val jsonBody = buildFinancialRequestJson(token, apiName, tsCode, fields)
            val req = Request.Builder()
                .url("http://api.tushare.pro")
                .post(jsonBody.toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}: $text")
                }
                parseTushareEnvelope(text)
            }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
