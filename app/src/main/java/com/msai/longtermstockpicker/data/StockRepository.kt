package com.msai.longtermstockpicker.data

import com.msai.longtermstockpicker.domain.OwnershipInfo
import com.msai.longtermstockpicker.domain.Quote
import com.msai.longtermstockpicker.domain.ScoreResult
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

class StockRepository(
    private val api: TushareApiClient,
    private val cache: LocalJsonCache,
    private val ownershipCsvLoader: OwnershipCsvLoader,
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
        if (!forceRefresh && useLocalCache) {
            val cached = cache.read(tsCode)
            val meta = cache.readDailyMeta(tsCode)
            if (cached != null && meta?.lastTradeDate != null && meta.lastTradeDate >= endDate) {
                val filtered = cached.quotes.filter { it.tradeDate >= startDate && it.tradeDate <= endDate }
                if (filtered.isNotEmpty()) {
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
                val filtered = merged.filter { it.tradeDate >= startDate && it.tradeDate <= endDate }
                return Result.success(DailyLoadResult(filtered, "增量"))
            }
            if (cached != null && cached.endDate == endDate) {
                val filtered = cached.quotes.filter { it.tradeDate >= startDate && it.tradeDate <= endDate }
                if (filtered.isNotEmpty()) return Result.success(DailyLoadResult(filtered, "缓存"))
            }
        }

        val envResult = api.postDaily(token, tsCode, startDate, endDate)
        val env = envResult.getOrElse { return Result.failure(it) }
        if (env.code != 0) {
            return Result.failure(Exception(env.msg ?: "Tushare code=${env.code}"))
        }
        val quotes = parseDailyQuotes(env).filter { it.tradeDate >= startDate && it.tradeDate <= endDate }
        cache.write(tsCode, endDate, quotes)
        return Result.success(DailyLoadResult(quotes, "网络"))
    }

    suspend fun loadStockBasic(token: String): Result<Map<String, StockBasicInfo>> {
        val cached = cache.readStockBasic()
        if (!cached.isNullOrEmpty()) {
            return Result.success(cached.associateBy { it.tsCode })
        }

        val env = api.postStockBasic(token).getOrElse { return Result.failure(it) }
        if (env.code != 0) {
            return Result.failure(Exception(env.msg ?: "Tushare code=${env.code}"))
        }
        val items = parseStockBasic(env)
        cache.writeStockBasic(items)
        return Result.success(items.associateBy { it.tsCode })
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

    private companion object {
        const val FINANCIAL_FRESH_MS = 7L * 24L * 60L * 60L * 1000L
    }
}
