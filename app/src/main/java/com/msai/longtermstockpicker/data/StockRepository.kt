package com.msai.longtermstockpicker.data

import com.msai.longtermstockpicker.domain.OwnershipInfo
import com.msai.longtermstockpicker.domain.Quote
import com.msai.longtermstockpicker.domain.ScoreResult
import com.msai.longtermstockpicker.domain.FinancialSafetyCalculator
import com.msai.longtermstockpicker.domain.LongTermScoreEngine
import com.msai.longtermstockpicker.data.db.AppDatabase
import com.msai.longtermstockpicker.data.db.DailyQuoteEntity
import com.msai.longtermstockpicker.data.db.FinancialSnapshotEntity
import com.msai.longtermstockpicker.data.db.ScoreResultEntity
import com.msai.longtermstockpicker.data.db.UpdateMetaEntity
import com.msai.longtermstockpicker.data.db.toEntity
import com.msai.longtermstockpicker.data.db.toModel
import com.msai.longtermstockpicker.data.db.toScoreResult
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class DailyLoadResult(
    val quotes: List<Quote>,
    val source: String,
)

data class FinancialLoadResult(
    val data: FinancialStatementData?,
    val source: String,
)

data class DatabaseStats(
    val stockBasicCount: Int = 0,
    val dailyQuoteCount: Int = 0,
    val latestTradeDate: String? = null,
    val hasTenYearHistory: Boolean = false,
    val latestScoreDate: String? = null,
    val latestScoreCount: Int = 0,
)

data class UpdateDailyResult(
    val tradeDate: String,
    val upsertRows: Int,
    val tsCodeCount: Int,
    val startedAt: Long,
    val finishedAt: Long,
    val durationMs: Long,
    val errorMessage: String? = null,
)

data class JsonImportResult(
    val stockBasicCount: Int = 0,
    val dailyFileCount: Int = 0,
    val dailyQuoteCount: Int = 0,
    val financialFileCount: Int = 0,
    val financialSnapshotCount: Int = 0,
    val scoreResultCount: Int = 0,
    val failedFileCount: Int = 0,
    val durationMs: Long = 0,
)

data class RecomputeScoresResult(
    val tradeDate: String,
    val scannedCount: Int,
    val scoredCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val startedAt: Long,
    val finishedAt: Long,
    val durationMs: Long,
)

class StockRepository(
    private val api: TushareApiClient,
    private val cache: LocalJsonCache,
    private val ownershipCsvLoader: OwnershipCsvLoader,
    private val database: AppDatabase? = null,
) {

    suspend fun loadDaily(
        token: String,
        tsCode: String,
        startDate: String,
        endDate: String,
        useLocalCache: Boolean,
        forceRefresh: Boolean,
    ): Result<List<Quote>> = loadDailyWithSource(
        token = token,
        tsCode = tsCode,
        startDate = startDate,
        endDate = endDate,
        useLocalCache = useLocalCache,
        forceRefresh = forceRefresh,
    ).map { it.quotes }

    suspend fun loadDailyWithSource(
        token: String,
        tsCode: String,
        startDate: String,
        endDate: String,
        useLocalCache: Boolean,
        forceRefresh: Boolean,
    ): Result<DailyLoadResult> {
        val db = database
        if (db != null && !forceRefresh && useLocalCache) {
            val dbQuotes = db.dailyQuoteDao().getQuotes(tsCode, startDate, endDate).map { it.toModel() }
            val dbLatest = db.dailyQuoteDao().getLatestTradeDate(tsCode)
            if (dbQuotes.isNotEmpty() && dbLatest != null && dbLatest >= endDate) {
                return Result.success(DailyLoadResult(dbQuotes, "数据库"))
            }
        }
        if (!forceRefresh && useLocalCache) {
            val cached = cache.read(tsCode)
            val meta = cache.readDailyMeta(tsCode)
            if (cached != null && meta?.lastTradeDate != null && meta.lastTradeDate >= endDate) {
                val filtered = cached.quotes.filter { it.tradeDate >= startDate && it.tradeDate <= endDate }
                if (filtered.isNotEmpty()) {
                    db?.dailyQuoteDao()?.upsertAll(filtered.map { it.toEntity() })
                    return Result.success(DailyLoadResult(filtered, "缓存"))
                }
            }
            if (cached != null && meta?.lastTradeDate != null && meta.lastTradeDate < endDate) {
                val incStartDate = nextDate(meta.lastTradeDate)
                val envResult = api.postDaily(token, tsCode, incStartDate, endDate)
                val env = envResult.getOrElse { return Result.failure(it) }
                if (env.code != 0) {
                    return Result.failure(Exception(env.msg ?: "Tushare code=${env.code}"))
                }
                val incoming = parseDailyQuotes(env).filter { it.tradeDate >= incStartDate && it.tradeDate <= endDate }
                val merged = (cached.quotes + incoming)
                    .associateBy { it.tradeDate }
                    .values
                    .sortedBy { it.tradeDate }
                cache.write(tsCode, endDate, merged)
                db?.dailyQuoteDao()?.upsertAll(merged.map { it.toEntity() })
                val filtered = merged.filter { it.tradeDate >= startDate && it.tradeDate <= endDate }
                return Result.success(DailyLoadResult(filtered, "增量"))
            }
            if (cached != null && cached.endDate == endDate) {
                val filtered = cached.quotes.filter { it.tradeDate >= startDate && it.tradeDate <= endDate }
                if (filtered.isNotEmpty()) {
                    db?.dailyQuoteDao()?.upsertAll(filtered.map { it.toEntity() })
                    return Result.success(DailyLoadResult(filtered, "缓存"))
                }
            }
        }

        val envResult = api.postDaily(token, tsCode, startDate, endDate)
        val env = envResult.getOrElse { return Result.failure(it) }
        if (env.code != 0) {
            return Result.failure(Exception(env.msg ?: "Tushare code=${env.code}"))
        }
        val quotes = parseDailyQuotes(env).filter { it.tradeDate >= startDate && it.tradeDate <= endDate }
        cache.write(tsCode, endDate, quotes)
        db?.dailyQuoteDao()?.upsertAll(quotes.map { it.toEntity() })
        return Result.success(DailyLoadResult(quotes, "网络"))
    }

    suspend fun loadStockBasic(token: String): Result<Map<String, StockBasicInfo>> {
        val db = database
        val dbItems = db?.stockBasicDao()?.getAll().orEmpty()
        if (dbItems.isNotEmpty()) return Result.success(dbItems.map { it.toModel() }.associateBy { it.tsCode })

        val cached = cache.readStockBasic()
        if (!cached.isNullOrEmpty()) {
            db?.stockBasicDao()?.upsertAll(cached.map { it.toEntity() })
            return Result.success(cached.associateBy { it.tsCode })
        }

        val env = api.postStockBasic(token).getOrElse { return Result.failure(it) }
        if (env.code != 0) {
            return Result.failure(Exception(env.msg ?: "Tushare code=${env.code}"))
        }
        val items = parseStockBasic(env)
        cache.writeStockBasic(items)
        db?.stockBasicDao()?.upsertAll(items.map { it.toEntity() })
        return Result.success(items.associateBy { it.tsCode })
    }

    suspend fun updateDailyByTradeDate(token: String, tradeDate: String): UpdateDailyResult {
        val startedAt = System.currentTimeMillis()
        val env = api.postDailyByTradeDate(token, tradeDate).getOrElse { e ->
            val finishedAt = System.currentTimeMillis()
            return UpdateDailyResult(tradeDate, 0, 0, startedAt, finishedAt, finishedAt - startedAt, e.message ?: e.toString())
        }
        if (env.code != 0) {
            val finishedAt = System.currentTimeMillis()
            return UpdateDailyResult(tradeDate, 0, 0, startedAt, finishedAt, finishedAt - startedAt, env.msg ?: "Tushare code=${env.code}")
        }
        val quotes = parseDailyQuotes(env)
        database?.dailyQuoteDao()?.upsertAll(quotes.map { it.toEntity() })
        database?.updateMetaDao()?.upsert(UpdateMetaEntity("latest_trade_date", tradeDate, System.currentTimeMillis()))
        val finishedAt = System.currentTimeMillis()
        return UpdateDailyResult(
            tradeDate = tradeDate,
            upsertRows = quotes.size,
            tsCodeCount = quotes.map { it.tsCode }.distinct().size,
            startedAt = startedAt,
            finishedAt = finishedAt,
            durationMs = finishedAt - startedAt,
            errorMessage = null,
        )
    }

    suspend fun databaseStats(): DatabaseStats {
        val db = database ?: return DatabaseStats()
        val latestScoreDate = db.scoreResultDao().getLatestScoreDate()
        val latestDaily = db.dailyQuoteDao().getMaxTradeDate()
        val hasTenYears = latestDaily?.let {
            runCatching {
                val minNeeded = LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE)
                    .minusYears(10)
                    .format(DateTimeFormatter.BASIC_ISO_DATE)
                db.dailyQuoteDao().countAll() > 0 && db.dailyQuoteDao().getMaxTradeDate() != null && minNeeded.isNotBlank()
            }.getOrDefault(false)
        } ?: false
        return DatabaseStats(
            stockBasicCount = db.stockBasicDao().count(),
            dailyQuoteCount = db.dailyQuoteDao().countAll(),
            latestTradeDate = latestDaily,
            hasTenYearHistory = hasTenYears,
            latestScoreDate = latestScoreDate,
            latestScoreCount = latestScoreDate?.let { db.scoreResultDao().countByTradeDate(it) } ?: 0,
        )
    }

    suspend fun saveScoreResult(result: ScoreResult) {
        val tradeDate = result.latestTradeDate ?: result.tsCode.takeIf { false }.orEmpty()
        if (tradeDate.isNotBlank()) database?.scoreResultDao()?.upsert(result.toEntity(tradeDate))
    }

    suspend fun loadLatestScoreResults(limit: Int = 100): List<ScoreResult> {
        val dao = database?.scoreResultDao() ?: return emptyList()
        return dao.getTopScoresByLatestTradeDate(limit).map { it.toScoreResult() }
    }

    suspend fun recomputeScoresFromDatabase(
        token: String,
        endDate: String,
        maxCount: Int = 1000,
    ): RecomputeScoresResult {
        val db = database ?: return RecomputeScoresResult(endDate, 0, 0, 0, 0, System.currentTimeMillis(), System.currentTimeMillis(), 0)
        val startedAt = System.currentTimeMillis()
        val startDate = tenYearStartDate(endDate)
        val ownershipMap = loadOwnershipInfo()
        val candidates = db.stockBasicDao().getAll()
            .asSequence()
            .filter { it.tsCode.isNotBlank() && it.name.isNotBlank() }
            .filterNot { it.isSt }
            .filterNot { isListedLessThanFiveYears(it.listDate.orEmpty(), endDate) }
            .take(maxCount.coerceIn(1, 5000))
            .toList()
        var scored = 0
        var skipped = 0
        var failed = 0
        for (basicEntity in candidates) {
            val basic = basicEntity.toModel()
            val quotes = db.dailyQuoteDao().getQuotes(basic.tsCode, startDate, endDate).map { it.toModel() }
            if (quotes.size < 500) {
                skipped++
                continue
            }
            val result = runCatching {
                val financialLoad = loadFinancialWithSource(token, basic.tsCode).getOrNull()
                LongTermScoreEngine.score(
                    tsCode = basic.tsCode,
                    dailies = quotes,
                    basic = basic,
                    financial = FinancialSafetyCalculator.computeFromFinancial(financialLoad?.data),
                    ownership = ownershipMap[basic.tsCode],
                    dailyDataSource = "数据库",
                    financialDataSource = financialLoad?.source ?: "默认分",
                )
            }.getOrElse {
                failed++
                null
            } ?: continue
            saveScoreResult(result)
            scored++
        }
        val finishedAt = System.currentTimeMillis()
        return RecomputeScoresResult(
            tradeDate = endDate,
            scannedCount = candidates.size,
            scoredCount = scored,
            skippedCount = skipped,
            failedCount = failed,
            startedAt = startedAt,
            finishedAt = finishedAt,
            durationMs = finishedAt - startedAt,
        )
    }

    suspend fun importJsonCacheToDatabase(): JsonImportResult {
        val db = database ?: return JsonImportResult()
        val startedAt = System.currentTimeMillis()
        var stockBasicCount = 0
        var dailyFileCount = 0
        var dailyQuoteCount = 0
        var financialFileCount = 0
        var financialSnapshotCount = 0
        var scoreResultCount = 0
        var failedFileCount = 0

        runCatching {
            val basic = cache.readStockBasic().orEmpty()
            if (basic.isNotEmpty()) {
                db.stockBasicDao().upsertAll(basic.map { it.toEntity() })
                stockBasicCount = basic.size
            }
        }.onFailure { failedFileCount++ }

        cache.readAllDailyCaches().forEach { (_, daily) ->
            runCatching {
                dailyFileCount++
                db.dailyQuoteDao().upsertAll(daily.quotes.map { it.toEntity() })
                dailyQuoteCount += daily.quotes.size
            }.onFailure { failedFileCount++ }
        }

        cache.readAllFinancialCaches().forEach { (tsCode, data) ->
            runCatching {
                financialFileCount++
                val breakdown = FinancialSafetyCalculator.computeFromFinancial(data)
                val reportDate = breakdown.reportPeriod ?: data.latestReportDate() ?: return@runCatching
                db.financialSnapshotDao().upsert(
                    FinancialSnapshotEntity(
                        tsCode = tsCode,
                        reportDate = reportDate,
                        annDate = null,
                        shortDebtCoverage = breakdown.shortDebtCoverage,
                        interestBearingDebtRatio = breakdown.interestBearingDebtRatio,
                        cashflowQuality = breakdown.operatingCashflowQuality,
                        debtToAssets = breakdown.debtToAssets,
                        financialSafetyScore = breakdown.totalScore,
                        riskText = breakdown.note,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                financialSnapshotCount++
            }.onFailure { failedFileCount++ }
        }

        runCatching {
            val partial = cache.readPartialResults()
            partial.filter { it.latestTradeDate != null }.forEach {
                db.scoreResultDao().upsert(it.toEntity(it.latestTradeDate.orEmpty()))
                scoreResultCount++
            }
        }.onFailure { failedFileCount++ }

        val finishedAt = System.currentTimeMillis()
        return JsonImportResult(
            stockBasicCount = stockBasicCount,
            dailyFileCount = dailyFileCount,
            dailyQuoteCount = dailyQuoteCount,
            financialFileCount = financialFileCount,
            financialSnapshotCount = financialSnapshotCount,
            scoreResultCount = scoreResultCount,
            failedFileCount = failedFileCount,
            durationMs = finishedAt - startedAt,
        )
    }

    suspend fun loadFinancial(token: String, tsCode: String): Result<FinancialStatementData> {
        return loadFinancialWithSource(token, tsCode).map { it.data ?: FinancialStatementData() }
    }

    suspend fun loadFinancialWithSource(token: String, tsCode: String): Result<FinancialLoadResult> {
        val cached = cache.readFinancial(tsCode)
        val meta = cache.readFinancialMeta(tsCode)
        if (cached != null && meta != null && System.currentTimeMillis() - meta.updatedAt < FINANCIAL_FRESH_MS) {
            return Result.success(FinancialLoadResult(cached, "缓存"))
        }

        val balanceSheets = fetchFinancialRows(
            token = token,
            tsCode = tsCode,
            apiName = "balance_sheet",
            fields = "ts_code,end_date,money_cap,st_borr,non_cur_liab_due_1y,lt_borr,bond_payable,total_assets,total_liab",
            parser = ::parseBalanceSheet,
        )
        val cashflows = fetchFinancialRows(
            token = token,
            tsCode = tsCode,
            apiName = "cashflow",
            fields = "ts_code,end_date,n_cashflow_act",
            parser = ::parseCashflow,
        )
        val incomes = fetchFinancialRows(
            token = token,
            tsCode = tsCode,
            apiName = "income",
            fields = "ts_code,end_date,n_income",
            parser = ::parseIncome,
        )
        val indicators = fetchFinancialRows(
            token = token,
            tsCode = tsCode,
            apiName = "fina_indicator",
            fields = "ts_code,end_date,debt_to_assets",
            parser = ::parseFinaIndicator,
        )

        val data = FinancialStatementData(
            balanceSheets = balanceSheets.takeLatestFour(),
            cashflows = cashflows.takeLatestFour(),
            incomes = incomes.takeLatestFour(),
            finaIndicators = indicators.takeLatestFour(),
        )
        if (data.balanceSheets.isEmpty() && data.cashflows.isEmpty() && data.incomes.isEmpty() && data.finaIndicators.isEmpty()) {
            cache.writeFinancialMeta(
                FinancialMetaEntry(
                    tsCode = tsCode,
                    updatedAt = System.currentTimeMillis(),
                    status = "empty",
                ),
            )
            return Result.failure(Exception("financial data empty"))
        }
        cache.writeFinancial(tsCode, data)
        return Result.success(FinancialLoadResult(data, "网络"))
    }

    fun clearAllDailyJsonFiles(): Int = cache.clearAllDailyJsonFiles()

    fun loadOwnershipInfo(): Map<String, OwnershipInfo> {
        return ownershipCsvLoader.load()
    }

    fun readScanSession(): ScanSession? = cache.readScanSession()

    fun writeScanSession(session: ScanSession) {
        cache.writeScanSession(session)
    }

    fun readPartialResults(): List<ScoreResult> = cache.readPartialResults()

    fun writePartialResults(results: List<ScoreResult>) {
        cache.writePartialResults(results)
    }

    fun clearScanProgress() {
        cache.clearScanProgress()
    }

    fun readRetryQueue(): List<RetryItem> = cache.readRetryQueue()

    fun recordRetryFailure(tsCode: String, name: String?, reason: String) {
        cache.upsertRetryFailure(tsCode, name, reason)
    }

    fun removeRetryItem(tsCode: String) {
        cache.removeRetryItem(tsCode)
    }

    private suspend fun <T> fetchFinancialRows(
        token: String,
        tsCode: String,
        apiName: String,
        fields: String,
        parser: (TushareEnvelope) -> List<T>,
    ): List<T> {
        val env = api.postFinancial(token, apiName, tsCode, fields).getOrElse { return emptyList() }
        if (env.code != 0) return emptyList()
        return parser(env)
    }

    private fun <T> List<T>.takeLatestFour(): List<T> {
        fun endDateOf(item: T): String = when (item) {
            is BalanceSheetRow -> item.endDate
            is CashflowRow -> item.endDate
            is IncomeRow -> item.endDate
            is FinaIndicatorRow -> item.endDate
            else -> ""
        }
        return sortedByDescending { endDateOf(it) }.take(4)
    }

    private fun nextDate(yyyymmdd: String): String {
        return LocalDate.parse(yyyymmdd, DateTimeFormatter.BASIC_ISO_DATE)
            .plusDays(1)
            .format(DateTimeFormatter.BASIC_ISO_DATE)
    }

    private fun tenYearStartDate(endDate: String): String {
        return LocalDate.parse(endDate, DateTimeFormatter.BASIC_ISO_DATE)
            .minusYears(10)
            .format(DateTimeFormatter.BASIC_ISO_DATE)
    }

    private fun isListedLessThanFiveYears(listDate: String, endDate: String): Boolean {
        if (listDate.length != 8) return false
        return runCatching {
            LocalDate.parse(listDate, DateTimeFormatter.BASIC_ISO_DATE)
                .isAfter(LocalDate.parse(endDate, DateTimeFormatter.BASIC_ISO_DATE).minusYears(5))
        }.getOrDefault(false)
    }

    private fun FinancialStatementData.latestReportDate(): String? {
        return listOfNotNull(
            balanceSheets.maxOfOrNull { it.endDate },
            cashflows.maxOfOrNull { it.endDate },
            incomes.maxOfOrNull { it.endDate },
            finaIndicators.maxOfOrNull { it.endDate },
        ).maxOrNull()
    }

    private companion object {
        const val FINANCIAL_FRESH_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
