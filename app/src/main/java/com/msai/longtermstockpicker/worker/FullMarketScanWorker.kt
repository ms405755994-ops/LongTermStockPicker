package com.msai.longtermstockpicker.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.msai.longtermstockpicker.BuildConfig
import com.msai.longtermstockpicker.data.LocalJsonCache
import com.msai.longtermstockpicker.data.OwnershipCsvLoader
import com.msai.longtermstockpicker.data.ScanSession
import com.msai.longtermstockpicker.data.StockBasicInfo
import com.msai.longtermstockpicker.data.StockRepository
import com.msai.longtermstockpicker.data.TushareApiClient
import com.msai.longtermstockpicker.domain.FinancialSafetyCalculator
import com.msai.longtermstockpicker.domain.LongTermScoreEngine
import com.msai.longtermstockpicker.domain.OwnershipInfo
import com.msai.longtermstockpicker.domain.ScoreResult
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class FullMarketScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val repository = StockRepository(
        TushareApiClient(),
        LocalJsonCache(appContext),
        OwnershipCsvLoader(appContext),
    )

    override suspend fun doWork(): Result {
        val token = BuildConfig.TUSHARE_TOKEN
        if (token.isBlank()) return Result.failure()

        val startDate = inputData.getString(KEY_START_DATE).orEmpty()
        val endDate = inputData.getString(KEY_END_DATE).orEmpty()
        val useLocalCache = inputData.getBoolean(KEY_USE_LOCAL_CACHE, true)
        val forceRefresh = inputData.getBoolean(KEY_FORCE_REFRESH, false)
        val maxScanCount = inputData.getInt(KEY_MAX_SCAN_COUNT, 100).coerceIn(1, 1000)
        if (startDate.length != 8 || endDate.length != 8) return Result.failure()

        ensureChannel()
        setForeground(buildForegroundInfo(0, maxScanCount, 0, 0))

        val basicMap = repository.loadStockBasic(token).getOrElse { return Result.failure() }
        val ownershipMap = repository.loadOwnershipInfo()
        val targetCodes = basicMap.values
            .asSequence()
            .map { it.tsCode.trim().uppercase() }
            .filter { it.endsWith(".SZ") || it.endsWith(".SH") }
            .distinct()
            .sorted()
            .filter { code -> basicMap[code]?.hardExclusion(code, ownershipMap[code]) == null }
            .take(maxScanCount)
            .toList()
        if (targetCodes.isEmpty()) return Result.failure()

        val createdAt = now()
        var session = ScanSession(
            scanId = "worker-scan-$createdAt",
            mode = "full_market_worker",
            targetEndDate = endDate,
            maxScanCount = maxScanCount,
            candidateCodes = targetCodes,
            cursorIndex = 0,
            status = "running",
            successCount = 0,
            failedCount = 0,
            skippedCount = 0,
            startedAt = createdAt,
            updatedAt = createdAt,
        )
        repository.clearScanProgress()
        repository.writeScanSession(session)
        repository.writePartialResults(emptyList())

        val acc = mutableListOf<ScoreResult>()
        var ok = 0
        var fail = 0
        var skipped = 0

        for ((index, code) in targetCodes.withIndex()) {
            if (isStopped) {
                repository.writeScanSession(
                    session.copy(status = "cancelled", updatedAt = now()),
                )
                return Result.success()
            }

            setForeground(buildForegroundInfo(index + 1, targetCodes.size, ok, fail))
            val basic = basicMap[code]
            val ownership = ownershipMap[code]
            val result = scoreOneCode(
                token = token,
                code = code,
                startDate = startDate,
                endDate = endDate,
                useLocalCache = useLocalCache,
                forceRefresh = forceRefresh,
                basic = basic,
                ownership = ownership,
            )

            when {
                result.isScored && result.errorMessage == null -> {
                    ok++
                    repository.removeRetryItem(result.tsCode)
                }
                result.errorMessage != null -> {
                    fail++
                    repository.recordRetryFailure(
                        tsCode = result.tsCode,
                        name = result.stockName,
                        reason = result.errorMessage ?: result.exclusionReason ?: "扫描失败",
                    )
                }
                else -> skipped++
            }

            acc.removeAll { it.tsCode == result.tsCode }
            acc.add(result)
            session = session.copy(
                cursorIndex = index + 1,
                status = "running",
                successCount = ok,
                failedCount = fail,
                skippedCount = skipped,
                updatedAt = now(),
            )
            repository.writePartialResults(acc)
            repository.writeScanSession(session)
        }

        repository.writeScanSession(
            session.copy(
                cursorIndex = targetCodes.size,
                status = "completed",
                successCount = ok,
                failedCount = fail,
                skippedCount = skipped,
                updatedAt = now(),
            ),
        )
        setForeground(buildForegroundInfo(targetCodes.size, targetCodes.size, ok, fail))
        return Result.success()
    }

    private suspend fun scoreOneCode(
        token: String,
        code: String,
        startDate: String,
        endDate: String,
        useLocalCache: Boolean,
        forceRefresh: Boolean,
        basic: StockBasicInfo?,
        ownership: OwnershipInfo?,
    ): ScoreResult {
        val exclusion = basic?.hardExclusion(code, ownership)
        if (exclusion != null) return exclusion

        val loadResult = repository.loadDailyWithSource(
            token = token,
            tsCode = code,
            startDate = startDate,
            endDate = endDate,
            useLocalCache = useLocalCache,
            forceRefresh = forceRefresh,
        )

        return loadResult.fold(
            onSuccess = { daily ->
                val financialLoad = repository.loadFinancialWithSource(token, code).getOrNull()
                val financial = FinancialSafetyCalculator.computeFromFinancial(financialLoad?.data)
                LongTermScoreEngine.score(
                    tsCode = code,
                    dailies = daily.quotes,
                    basic = basic,
                    financial = financial,
                    ownership = ownership,
                    dailyDataSource = daily.source,
                    financialDataSource = financialLoad?.source ?: "默认分",
                )
            },
            onFailure = { e ->
                ScoreResult(
                    tsCode = code,
                    isScored = false,
                    exclusionReason = "拉取或解析失败",
                    totalScore = 0.0,
                    signalLevel = "-",
                    stockName = basic?.name,
                    industry = basic?.industry,
                    listDate = basic?.listDate,
                    market = basic?.market,
                    dailyDataSource = "失败",
                    financialDataSource = "默认分",
                    pricePositionScore = null,
                    macdMultiPeriodScore = null,
                    financialSafetyScore = null,
                    ownershipScore = ownership?.ownershipScore,
                    ownershipTierLabel = ownership?.companyType,
                    companyType = ownership?.companyType,
                    ownershipSource = ownership?.source,
                    ownershipRemark = ownership?.remark,
                    pricePercentile = null,
                    distanceToLow = null,
                    monthlyMacd = null,
                    weeklyMacd = null,
                    dailyMacd = null,
                    reason = "未能完成计算。",
                    riskWarnings = emptyList(),
                    errorMessage = e.message ?: e.toString(),
                )
            },
        )
    }

    private fun StockBasicInfo.hardExclusion(tsCode: String, ownership: OwnershipInfo?): ScoreResult? {
        val upperName = name.uppercase()
        return when {
            upperName.contains("*ST") || upperName.contains("ST") -> excludedResult(
                tsCode = tsCode,
                basic = this,
                ownership = ownership,
                reason = "ST 风险，不参与评分。",
                signalLevel = "剔除/不关注",
            )
            isListedLessThanFiveYears(listDate) -> excludedResult(
                tsCode = tsCode,
                basic = this,
                ownership = ownership,
                reason = "历史数据不足：上市不足5年。",
                signalLevel = "剔除/不关注",
            )
            else -> null
        }
    }

    private fun excludedResult(
        tsCode: String,
        basic: StockBasicInfo,
        ownership: OwnershipInfo?,
        reason: String,
        signalLevel: String,
    ): ScoreResult = ScoreResult(
        tsCode = tsCode,
        isScored = false,
        exclusionReason = reason,
        totalScore = 0.0,
        signalLevel = signalLevel,
        stockName = basic.name,
        industry = basic.industry,
        listDate = basic.listDate,
        market = basic.market,
        dailyDataSource = "跳过",
        financialDataSource = "跳过",
        pricePositionScore = null,
        macdMultiPeriodScore = null,
        financialSafetyScore = null,
        ownershipScore = ownership?.ownershipScore,
        ownershipTierLabel = ownership?.companyType,
        companyType = ownership?.companyType,
        ownershipSource = ownership?.source,
        ownershipRemark = ownership?.remark,
        pricePercentile = null,
        distanceToLow = null,
        monthlyMacd = null,
        weeklyMacd = null,
        dailyMacd = null,
        reason = reason,
        riskWarnings = listOf(reason),
    )

    private fun isListedLessThanFiveYears(listDate: String): Boolean {
        if (listDate.length != 8) return false
        return try {
            LocalDate.parse(listDate, DateTimeFormatter.BASIC_ISO_DATE)
                .isAfter(LocalDate.now().minusYears(5))
        } catch (_: DateTimeParseException) {
            false
        }
    }

    private fun buildForegroundInfo(current: Int, total: Int, success: Int, failed: Int): ForegroundInfo {
        val notification = buildNotification(current, total, success, failed)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(current: Int, total: Int, success: Int, failed: Int): Notification {
        val title = "全市场扫描进行中"
        val progressText = "进度 $current / $total  成功 $success  失败 $failed"
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(progressText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(progressText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), current.coerceAtMost(total), false)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "股票扫描",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        const val UNIQUE_WORK_NAME = "full_market_scan_worker"
        const val KEY_START_DATE = "startDate"
        const val KEY_END_DATE = "endDate"
        const val KEY_USE_LOCAL_CACHE = "useLocalCache"
        const val KEY_FORCE_REFRESH = "forceRefresh"
        const val KEY_MAX_SCAN_COUNT = "maxScanCount"

        private const val CHANNEL_ID = "full_market_scan"
        private const val NOTIFICATION_ID = 1001
    }
}
