package com.msai.longtermstockpicker.data

import com.msai.longtermstockpicker.domain.ScoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@Serializable
data class CloudScanFile(
    val updatedAt: String = "",
    val endDate: String = "",
    val maxScanCount: Int = 0,
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val skippedCount: Int = 0,
    val estimatedWaitMinutes: Int = 0,
    val results: List<CloudScoreItem> = emptyList(),
)

@Serializable
data class CloudScoreItem(
    val tsCode: String,
    val stockName: String? = null,
    val industry: String? = null,
    val totalScore: Double = 0.0,
    val signalLevel: String = "—",
    val isScored: Boolean = true,
    val exclusionReason: String? = null,
    val errorMessage: String? = null,
    val dailyDataSource: String? = "云端",
    val financialDataSource: String? = "云端",
)

class CloudResultsClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun fetch(url: String): Result<CloudScanFile> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                json.decodeFromString(CloudScanFile.serializer(), text)
            }
        }
    }
}

fun CloudScoreItem.toScoreResult(): ScoreResult = ScoreResult(
    tsCode = tsCode,
    isScored = isScored,
    exclusionReason = exclusionReason,
    totalScore = totalScore,
    signalLevel = signalLevel,
    stockName = stockName,
    industry = industry,
    listDate = null,
    market = null,
    dailyDataSource = dailyDataSource,
    financialDataSource = financialDataSource,
    pricePositionScore = null,
    macdMultiPeriodScore = null,
    financialSafetyScore = null,
    ownershipScore = null,
    ownershipTierLabel = null,
    companyType = null,
    ownershipSource = null,
    ownershipRemark = null,
    pricePercentile = null,
    distanceToLow = null,
    monthlyMacd = null,
    weeklyMacd = null,
    dailyMacd = null,
    reason = "云端扫描结果。",
    riskWarnings = emptyList(),
    errorMessage = errorMessage,
)
