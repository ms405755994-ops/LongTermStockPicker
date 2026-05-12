package com.msai.longtermstockpicker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.msai.longtermstockpicker.data.CloudResultsClient
import com.msai.longtermstockpicker.data.LocalJsonCache
import com.msai.longtermstockpicker.data.OwnershipCsvLoader
import com.msai.longtermstockpicker.data.ScanSession
import com.msai.longtermstockpicker.data.StockBasicInfo
import com.msai.longtermstockpicker.data.StockRepository
import com.msai.longtermstockpicker.data.TushareApiClient
import com.msai.longtermstockpicker.data.toScoreResult
import com.msai.longtermstockpicker.domain.FinancialSafetyCalculator
import com.msai.longtermstockpicker.domain.LongTermScoreEngine
import com.msai.longtermstockpicker.domain.OwnershipInfo
import com.msai.longtermstockpicker.domain.ScoreResult
import com.msai.longtermstockpicker.worker.FullMarketScanWorker
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class RunProgress(
    val currentIndex: Int,
    val total: Int,
    val currentCode: String,
    val successCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val detail: String?,
)

data class ScanSummary(
    val scanCount: Int,
    val successCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val cacheHitCount: Int = 0,
    val networkRequestCount: Int = 0,
    val incrementalUpdateCount: Int = 0,
    val skippedUpdatedCount: Int = 0,
)

data class RetryQueueSummary(
    val totalCount: Int,
    val retryableCount: Int,
)

class StockPickerViewModel(app: Application) : AndroidViewModel(app) {

    private val workManager = WorkManager.getInstance(app.applicationContext)

    private val repository = StockRepository(
        TushareApiClient(),
        LocalJsonCache(app.applicationContext),
        OwnershipCsvLoader(app.applicationContext),
    )
    private val cloudResultsClient = CloudResultsClient()

    private var pauseRequested = false

    private val _results = MutableStateFlow<List<ScoreResult>>(emptyList())
    val results: StateFlow<List<ScoreResult>> = _results.asStateFlow()

    private val _scanSummary = MutableStateFlow<ScanSummary?>(null)
    val scanSummary: StateFlow<ScanSummary?> = _scanSummary.asStateFlow()

    private val _scanSession = MutableStateFlow<ScanSession?>(null)
    val scanSession: StateFlow<ScanSession?> = _scanSession.asStateFlow()

    private val _retryQueueSummary = MutableStateFlow(RetryQueueSummary(0, 0))
    val retryQueueSummary: StateFlow<RetryQueueSummary> = _retryQueueSummary.asStateFlow()

    private val _progress = MutableStateFlow<RunProgress?>(null)
    val progress: StateFlow<RunProgress?> = _progress.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _navigateToResults = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val navigateToResults: SharedFlow<Unit> = _navigateToResults.asSharedFlow()

    init {
        refreshScanStateFromDisk()
    }

    fun clearError() {
        _lastError.value = null
    }

    fun clearAllDailyJsonCache(): Int = repository.clearAllDailyJsonFiles()

    fun pauseScan() {
        pauseRequested = true
    }

    fun cancelScan() {
        pauseRequested = true
        workManager.cancelUniqueWork(FullMarketScanWorker.UNIQUE_WORK_NAME)
        repository.readScanSession()?.let { session ->
            if (session.status == "running") {
                repository.writeScanSession(
                    session.copy(status = "cancelled", updatedAt = now()),
                )
            }
        }
        _progress.value = null
        refreshScanStateFromDisk()
    }

    fun clearScanProgress() {
        pauseRequested = false
        workManager.cancelUniqueWork(FullMarketScanWorker.UNIQUE_WORK_NAME)
        repository.clearScanProgress()
        _scanSession.value = null
        _scanSummary.value = null
        _results.value = emptyList()
        _progress.value = null
        refreshRetryQueueSummary()
    }

    fun retryFailedItems(
        token: String,
        startDate: String,
        endDate: String,
        useLocalCache: Boolean,
        forceRefresh: Boolean,
    ) {
        if (token.isBlank()) {
            _lastError.value = "请先在 local.properties 配置 TUSHARE_TOKEN"
            return
        }
        val retryCodes = repository.readRetryQueue()
            .filter { it.retryCount < MAX_RETRY_COUNT && !it.permanentlyFailed }
            .map { it.tsCode }
            .distinct()
        if (retryCodes.isEmpty()) {
            _lastError.value = "没有可重试的失败项"
            refreshRetryQueueSummary()
            return
        }
        runBatch(
            token = token,
            codes = retryCodes,
            startDate = startDate,
            endDate = endDate,
            useLocalCache = useLocalCache,
            forceRefresh = forceRefresh,
            fullMarketScan = false,
        )
    }

    fun loadCloudResults(url: String) {
        if (url.isBlank()) {
            _lastError.value = "请先在 local.properties 配置 CLOUD_RESULTS_URL"
            return
        }
        viewModelScope.launch {
            _lastError.value = null
            _progress.value = RunProgress(
                currentIndex = 0,
                total = 1,
                currentCode = "",
                successCount = 0,
                failedCount = 0,
                skippedCount = 0,
                detail = "读取云端结果",
            )
            cloudResultsClient.fetch(url).fold(
                onSuccess = { cloud ->
                    val mapped = cloud.results.map { it.toScoreResult() }
                    _results.value = mapped.sortedByDescending { it.sortKey }
                    _scanSummary.value = ScanSummary(
                        scanCount = cloud.maxScanCount,
                        successCount = cloud.successCount,
                        failedCount = cloud.failedCount,
                        skippedCount = cloud.skippedCount,
                        networkRequestCount = 0,
                    )
                    _progress.value = null
                    if (mapped.isNotEmpty()) _navigateToResults.tryEmit(Unit)
                },
                onFailure = { e ->
                    _progress.value = null
                    _lastError.value = "云端结果读取失败：${e.message ?: e.toString()}"
                },
            )
        }
    }

    fun continueLastScan(
        token: String,
        startDate: String,
        useLocalCache: Boolean,
        forceRefresh: Boolean,
    ) {
        if (token.isBlank()) {
            _lastError.value = "请先在 local.properties 配置 TUSHARE_TOKEN"
            return
        }
        val session = repository.readScanSession()
        if (session == null) {
            _lastError.value = "没有可继续的扫描任务"
            refreshScanStateFromDisk()
            return
        }
        if (session.status == "completed") {
            _lastError.value = "上次扫描已完成"
            refreshScanStateFromDisk()
            return
        }
        viewModelScope.launch {
            pauseRequested = false
            val partial = repository.readPartialResults()
            _results.value = partial.sortedByDescending { it.sortKey }
            executeScanSession(
                token = token,
                startDate = startDate,
                endDate = session.targetEndDate,
                useLocalCache = useLocalCache,
                forceRefresh = forceRefresh,
                initialSession = session.copy(status = "running", updatedAt = now()),
                initialResults = partial,
            )
        }
    }

    fun runBatch(
        token: String,
        codes: List<String>,
        startDate: String,
        endDate: String,
        useLocalCache: Boolean,
        forceRefresh: Boolean,
        fullMarketScan: Boolean = false,
        maxScanCount: Int = 100,
    ) {
        if (token.isBlank()) {
            _lastError.value = "请先在 local.properties 配置 TUSHARE_TOKEN"
            return
        }

        viewModelScope.launch {
            pauseRequested = false
            _lastError.value = null
            _results.value = emptyList()
            _scanSummary.value = null
            val manualCodes = codes.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
            if (!fullMarketScan && manualCodes.isEmpty()) {
                _lastError.value = "请至少输入一只股票代码"
                return@launch
            }
            if (fullMarketScan) {
                enqueueFullMarketScanWorker(
                    startDate = startDate,
                    endDate = endDate,
                    useLocalCache = useLocalCache,
                    forceRefresh = forceRefresh,
                    maxScanCount = maxScanCount,
                )
                return@launch
            }

            val basicMap = repository.loadStockBasic(token).getOrElse {
                _lastError.value = "stock_basic 加载失败，将仅使用股票代码继续计算"
                emptyMap()
            }
            if (fullMarketScan && basicMap.isEmpty()) {
                _lastError.value = "全市场扫描需要先加载 stock_basic 候选池"
                return@launch
            }
            val ownershipMap = repository.loadOwnershipInfo()
            val safeMaxScanCount = maxScanCount.coerceIn(1, 1000)
            val targetCodes = if (fullMarketScan) {
                basicMap.values
                    .asSequence()
                    .map { it.tsCode.trim().uppercase() }
                    .filter { it.endsWith(".SZ") || it.endsWith(".SH") }
                    .distinct()
                    .sorted()
                    .filter { code -> basicMap[code]?.hardExclusion(code, ownershipMap[code]) == null }
                    .take(safeMaxScanCount)
                    .toList()
            } else {
                manualCodes
            }
            if (targetCodes.isEmpty()) {
                _lastError.value = "没有可扫描的股票候选"
                return@launch
            }

            val session = if (fullMarketScan) {
                val createdAt = now()
                ScanSession(
                    scanId = "scan-$createdAt",
                    mode = "full_market",
                    targetEndDate = endDate,
                    maxScanCount = safeMaxScanCount,
                    candidateCodes = targetCodes,
                    cursorIndex = 0,
                    status = "running",
                    successCount = 0,
                    failedCount = 0,
                    skippedCount = 0,
                    startedAt = createdAt,
                    updatedAt = createdAt,
                ).also {
                    repository.clearScanProgress()
                    repository.writeScanSession(it)
                    repository.writePartialResults(emptyList())
                    _scanSession.value = it
                }
            } else {
                null
            }

            executeScanSession(
                token = token,
                startDate = startDate,
                endDate = endDate,
                useLocalCache = useLocalCache,
                forceRefresh = forceRefresh,
                initialSession = session,
                initialResults = emptyList(),
                manualCodes = targetCodes,
            )
        }
    }

    private fun enqueueFullMarketScanWorker(
        startDate: String,
        endDate: String,
        useLocalCache: Boolean,
        forceRefresh: Boolean,
        maxScanCount: Int,
    ) {
        val safeMaxScanCount = maxScanCount.coerceIn(1, 1000)
        _results.value = emptyList()
        _scanSummary.value = ScanSummary(
            scanCount = safeMaxScanCount,
            successCount = 0,
            failedCount = 0,
            skippedCount = 0,
        )
        _progress.value = RunProgress(
            currentIndex = 0,
            total = safeMaxScanCount,
            currentCode = "",
            successCount = 0,
            failedCount = 0,
            skippedCount = 0,
            detail = "后台扫描已启动",
        )
        val request = OneTimeWorkRequestBuilder<FullMarketScanWorker>()
            .setInputData(
                workDataOf(
                    FullMarketScanWorker.KEY_START_DATE to startDate,
                    FullMarketScanWorker.KEY_END_DATE to endDate,
                    FullMarketScanWorker.KEY_USE_LOCAL_CACHE to useLocalCache,
                    FullMarketScanWorker.KEY_FORCE_REFRESH to forceRefresh,
                    FullMarketScanWorker.KEY_MAX_SCAN_COUNT to safeMaxScanCount,
                ),
            )
            .build()
        workManager.enqueueUniqueWork(
            FullMarketScanWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private suspend fun executeScanSession(
        token: String,
        startDate: String,
        endDate: String,
        useLocalCache: Boolean,
        forceRefresh: Boolean,
        initialSession: ScanSession?,
        initialResults: List<ScoreResult>,
        manualCodes: List<String>? = null,
    ) {
        var session = initialSession
        val targetCodes = session?.candidateCodes ?: manualCodes.orEmpty()
        if (targetCodes.isEmpty()) return

        val basicMap = repository.loadStockBasic(token).getOrElse {
            _lastError.value = "stock_basic 加载失败，将仅使用股票代码继续计算"
            emptyMap()
        }
        val ownershipMap = repository.loadOwnershipInfo()
        val acc = initialResults.toMutableList()
        var ok = session?.successCount ?: acc.count { it.isScored && it.errorMessage == null }
        var fail = session?.failedCount ?: acc.count { it.errorMessage != null }
        var skipped = session?.skippedCount ?: acc.count { !it.isScored && it.errorMessage == null }
        val startIndex = session?.cursorIndex?.coerceIn(0, targetCodes.size) ?: 0

        _progress.value = RunProgress(
            currentIndex = startIndex,
            total = targetCodes.size,
            currentCode = "",
            successCount = ok,
            failedCount = fail,
            skippedCount = skipped,
            detail = "运行中",
        )

        for (index in startIndex until targetCodes.size) {
            val code = targetCodes[index]
            _progress.value = RunProgress(
                currentIndex = index + 1,
                total = targetCodes.size,
                currentCode = code,
                successCount = ok,
                failedCount = fail,
                skippedCount = skipped,
                detail = null,
            )

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
            refreshRetryQueueSummary()
            acc.removeAll { it.tsCode == result.tsCode }
            acc.add(result)

            if (session != null) {
                val status = if (pauseRequested) "paused" else "running"
                session = session.copy(
                    cursorIndex = index + 1,
                    status = status,
                    successCount = ok,
                    failedCount = fail,
                    skippedCount = skipped,
                    updatedAt = now(),
                )
                repository.writePartialResults(acc)
                repository.writeScanSession(session)
                _scanSession.value = session
            }
            _results.value = acc.sortedByDescending { it.sortKey }
            _scanSummary.value = buildScanSummary(targetCodes.size, ok, fail, skipped, acc)

            if (pauseRequested) {
                _progress.value = RunProgress(
                    currentIndex = index + 1,
                    total = targetCodes.size,
                    currentCode = "",
                    successCount = ok,
                    failedCount = fail,
                    skippedCount = skipped,
                    detail = "暂停",
                )
                return
            }
        }

        session?.let {
            val completed = it.copy(
                cursorIndex = targetCodes.size,
                status = "completed",
                successCount = ok,
                failedCount = fail,
                skippedCount = skipped,
                updatedAt = now(),
            )
            repository.writeScanSession(completed)
            repository.writePartialResults(acc)
            _scanSession.value = completed
        }
        _scanSummary.value = buildScanSummary(targetCodes.size, ok, fail, skipped, acc)
        _progress.value = RunProgress(
            currentIndex = targetCodes.size,
            total = targetCodes.size,
            currentCode = "",
            successCount = ok,
            failedCount = fail,
            skippedCount = skipped,
            detail = "完成",
        )

        val hasRankedScore = acc.any { it.isScored && it.errorMessage == null }
        if (hasRankedScore) {
            _navigateToResults.tryEmit(Unit)
        }
        refreshRetryQueueSummary()
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
                val financialSource = financialLoad?.source ?: "默认分"
                LongTermScoreEngine.score(
                    tsCode = code,
                    dailies = daily.quotes,
                    basic = basic,
                    financial = financial,
                    ownership = ownership,
                    dailyDataSource = daily.source,
                    financialDataSource = financialSource,
                )
            },
            onFailure = { e ->
                ScoreResult(
                    tsCode = code,
                    isScored = false,
                    exclusionReason = "拉取或解析失败",
                    totalScore = 0.0,
                    signalLevel = "—",
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

    fun refreshScanStateFromDisk() {
        val session = repository.readScanSession()
        val partial = repository.readPartialResults()
        _scanSession.value = session
        if (partial.isNotEmpty()) {
            _results.value = partial.sortedByDescending { it.sortKey }
        }
        session?.let {
            _scanSummary.value = ScanSummary(
                scanCount = it.candidateCodes.size,
                successCount = it.successCount,
                failedCount = it.failedCount,
                skippedCount = it.skippedCount,
                cacheHitCount = partial.count { result -> result.dailyDataSource == "缓存" },
                networkRequestCount = partial.count { result -> result.dailyDataSource == "网络" || result.dailyDataSource == "增量" },
                incrementalUpdateCount = partial.count { result -> result.dailyDataSource == "增量" },
                skippedUpdatedCount = partial.count { result -> result.dailyDataSource == "缓存" },
            )
            _progress.value = if (it.status == "running") {
                RunProgress(
                    currentIndex = it.cursorIndex,
                    total = it.candidateCodes.size,
                    currentCode = "",
                    successCount = it.successCount,
                    failedCount = it.failedCount,
                    skippedCount = it.skippedCount,
                    detail = "后台扫描中",
                )
            } else {
                null
            }
            if (it.status == "completed" && partial.any { result -> result.isScored && result.errorMessage == null }) {
                _navigateToResults.tryEmit(Unit)
            }
        }
        refreshRetryQueueSummary()
    }

    private fun refreshRetryQueueSummary() {
        val items = repository.readRetryQueue()
        _retryQueueSummary.value = RetryQueueSummary(
            totalCount = items.size,
            retryableCount = items.count { it.retryCount < MAX_RETRY_COUNT && !it.permanentlyFailed },
        )
    }

    private fun buildScanSummary(
        scanCount: Int,
        ok: Int,
        fail: Int,
        skipped: Int,
        results: List<ScoreResult>,
    ): ScanSummary = ScanSummary(
        scanCount = scanCount,
        successCount = ok,
        failedCount = fail,
        skippedCount = skipped,
        cacheHitCount = results.count { it.dailyDataSource == "缓存" },
        networkRequestCount = results.count { it.dailyDataSource == "网络" || it.dailyDataSource == "增量" },
        incrementalUpdateCount = results.count { it.dailyDataSource == "增量" },
        skippedUpdatedCount = results.count { it.dailyDataSource == "缓存" },
    )

    private fun now(): Long = System.currentTimeMillis()

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
            val parsed = LocalDate.parse(listDate, DateTimeFormatter.BASIC_ISO_DATE)
            parsed.isAfter(LocalDate.now().minusYears(5))
        } catch (_: DateTimeParseException) {
            false
        }
    }

    private companion object {
        const val MAX_RETRY_COUNT = 3
    }
}
