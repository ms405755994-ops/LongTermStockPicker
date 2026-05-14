package com.msai.longtermstockpicker.data

import android.content.Context
import com.msai.longtermstockpicker.domain.Quote
import com.msai.longtermstockpicker.domain.ScoreResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DailyCacheFile(
    val endDate: String,
    val quotes: List<Quote>,
)

@Serializable
data class StockBasicCacheFile(
    val items: List<StockBasicInfo>,
)

@Serializable
data class FinancialCacheFile(
    val data: FinancialStatementData,
)

@Serializable
data class ScanSession(
    val scanId: String,
    val mode: String,
    val targetEndDate: String,
    val maxScanCount: Int,
    val candidateCodes: List<String>,
    val cursorIndex: Int,
    val status: String,
    val successCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val startedAt: Long,
    val updatedAt: Long,
)

@Serializable
data class PartialResultsFile(
    val results: List<ScoreResult>,
)

@Serializable
data class RetryItem(
    val tsCode: String,
    val name: String? = null,
    val reason: String,
    val retryCount: Int,
    val lastFailedAt: Long,
    val permanentlyFailed: Boolean = false,
)

@Serializable
data class RetryQueueFile(
    val items: List<RetryItem> = emptyList(),
)

@Serializable
data class DailyMetaEntry(
    val tsCode: String,
    val lastTradeDate: String,
    val lastRequestEndDate: String,
    val rows: Int,
    val updatedAt: Long,
)

@Serializable
data class DailyMetaFile(
    val items: List<DailyMetaEntry> = emptyList(),
)

@Serializable
data class FinancialMetaEntry(
    val tsCode: String,
    val latestReportDate: String? = null,
    val latestAnnDate: String? = null,
    val updatedAt: Long,
    val status: String,
)

@Serializable
data class FinancialMetaFile(
    val items: List<FinancialMetaEntry> = emptyList(),
)

class LocalJsonCache(context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val root: File = File(context.filesDir, "cache/daily").apply { mkdirs() }
    private val basicRoot: File = File(context.filesDir, "cache/basic").apply { mkdirs() }
    private val financialRoot: File = File(context.filesDir, "cache/financial").apply { mkdirs() }
    private val scanRoot: File = File(context.filesDir, "cache/scan").apply { mkdirs() }
    private val metaRoot: File = File(context.filesDir, "cache/meta").apply { mkdirs() }
    private val stockBasicFile: File = File(basicRoot, "stock_basic.json")
    private val scanSessionFile: File = File(scanRoot, "current_scan_session.json")
    private val partialResultsFile: File = File(scanRoot, "partial_results.json")
    private val retryQueueFile: File = File(scanRoot, "retry_queue.json")
    private val dailyMetaFile: File = File(metaRoot, "daily_meta.json")
    private val financialMetaFile: File = File(metaRoot, "financial_meta.json")

    fun cacheFile(tsCode: String): File = File(root, "$tsCode.json")

    fun read(tsCode: String): DailyCacheFile? {
        val f = cacheFile(tsCode)
        if (!f.exists()) return null
        return runCatching {
            json.decodeFromString(DailyCacheFile.serializer(), f.readText())
        }.getOrNull()
    }

    fun write(tsCode: String, endDate: String, quotes: List<Quote>) {
        val payload = DailyCacheFile(endDate = endDate, quotes = quotes)
        val f = cacheFile(tsCode)
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(payload))
        writeDailyMeta(
            DailyMetaEntry(
                tsCode = tsCode,
                lastTradeDate = quotes.maxOfOrNull { it.tradeDate }.orEmpty(),
                lastRequestEndDate = endDate,
                rows = quotes.size,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun readAllDailyCaches(): List<Pair<String, DailyCacheFile>> {
        if (!root.exists()) return emptyList()
        return root.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
            ?.mapNotNull { file ->
                val tsCode = file.name.removeSuffix(".json")
                runCatching {
                    tsCode to json.decodeFromString(DailyCacheFile.serializer(), file.readText())
                }.getOrNull()
            }
            .orEmpty()
    }

    fun readStockBasic(): List<StockBasicInfo>? {
        if (!stockBasicFile.exists()) return null
        return runCatching {
            json.decodeFromString(StockBasicCacheFile.serializer(), stockBasicFile.readText()).items
        }.getOrNull()
    }

    fun writeStockBasic(items: List<StockBasicInfo>) {
        stockBasicFile.parentFile?.mkdirs()
        stockBasicFile.writeText(json.encodeToString(StockBasicCacheFile(items)))
    }

    fun readFinancial(tsCode: String): FinancialStatementData? {
        val f = financialCacheFile(tsCode)
        if (!f.exists()) return null
        return runCatching {
            json.decodeFromString(FinancialCacheFile.serializer(), f.readText()).data
        }.getOrNull()
    }

    fun readAllFinancialCaches(): List<Pair<String, FinancialStatementData>> {
        if (!financialRoot.exists()) return emptyList()
        return financialRoot.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }
            ?.mapNotNull { file ->
                val tsCode = file.name.removeSuffix(".json")
                runCatching {
                    tsCode to json.decodeFromString(FinancialCacheFile.serializer(), file.readText()).data
                }.getOrNull()
            }
            .orEmpty()
    }

    fun dailyJsonFileCount(): Int = root.listFiles()?.count { it.isFile && it.name.endsWith(".json", ignoreCase = true) } ?: 0

    fun financialJsonFileCount(): Int = financialRoot.listFiles()?.count { it.isFile && it.name.endsWith(".json", ignoreCase = true) } ?: 0

    fun writeFinancial(tsCode: String, data: FinancialStatementData) {
        val f = financialCacheFile(tsCode)
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(FinancialCacheFile(data)))
        writeFinancialMeta(
            FinancialMetaEntry(
                tsCode = tsCode,
                latestReportDate = data.latestReportDate(),
                latestAnnDate = null,
                updatedAt = System.currentTimeMillis(),
                status = "ok",
            ),
        )
    }

    private fun financialCacheFile(tsCode: String): File = File(financialRoot, "$tsCode.json")

    fun readDailyMeta(tsCode: String): DailyMetaEntry? = readDailyMetaMap()[tsCode]

    fun writeDailyMeta(entry: DailyMetaEntry) {
        val next = readDailyMetaMap().toMutableMap()
        next[entry.tsCode] = entry
        dailyMetaFile.parentFile?.mkdirs()
        dailyMetaFile.writeText(json.encodeToString(DailyMetaFile(next.values.sortedBy { it.tsCode })))
    }

    fun readFinancialMeta(tsCode: String): FinancialMetaEntry? = readFinancialMetaMap()[tsCode]

    fun writeFinancialMeta(entry: FinancialMetaEntry) {
        val next = readFinancialMetaMap().toMutableMap()
        next[entry.tsCode] = entry
        financialMetaFile.parentFile?.mkdirs()
        financialMetaFile.writeText(json.encodeToString(FinancialMetaFile(next.values.sortedBy { it.tsCode })))
    }

    private fun readDailyMetaMap(): Map<String, DailyMetaEntry> {
        if (!dailyMetaFile.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString(DailyMetaFile.serializer(), dailyMetaFile.readText()).items.associateBy { it.tsCode }
        }.getOrDefault(emptyMap())
    }

    private fun readFinancialMetaMap(): Map<String, FinancialMetaEntry> {
        if (!financialMetaFile.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString(FinancialMetaFile.serializer(), financialMetaFile.readText()).items.associateBy { it.tsCode }
        }.getOrDefault(emptyMap())
    }

    fun readScanSession(): ScanSession? {
        if (!scanSessionFile.exists()) return null
        return runCatching {
            json.decodeFromString(ScanSession.serializer(), scanSessionFile.readText())
        }.getOrNull()
    }

    fun writeScanSession(session: ScanSession) {
        scanSessionFile.parentFile?.mkdirs()
        scanSessionFile.writeText(json.encodeToString(session))
    }

    fun readPartialResults(): List<ScoreResult> {
        if (!partialResultsFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(PartialResultsFile.serializer(), partialResultsFile.readText()).results
        }.getOrDefault(emptyList())
    }

    fun writePartialResults(results: List<ScoreResult>) {
        partialResultsFile.parentFile?.mkdirs()
        partialResultsFile.writeText(json.encodeToString(PartialResultsFile(results)))
    }

    fun clearScanProgress() {
        scanSessionFile.delete()
        partialResultsFile.delete()
    }

    fun readRetryQueue(): List<RetryItem> {
        if (!retryQueueFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(RetryQueueFile.serializer(), retryQueueFile.readText()).items
        }.getOrDefault(emptyList())
    }

    fun writeRetryQueue(items: List<RetryItem>) {
        retryQueueFile.parentFile?.mkdirs()
        retryQueueFile.writeText(json.encodeToString(RetryQueueFile(items.sortedBy { it.tsCode })))
    }

    fun upsertRetryFailure(tsCode: String, name: String?, reason: String) {
        val now = System.currentTimeMillis()
        val next = readRetryQueue().toMutableList()
        val existingIndex = next.indexOfFirst { it.tsCode == tsCode }
        if (existingIndex >= 0) {
            val existing = next[existingIndex]
            val retryCount = existing.retryCount + 1
            next[existingIndex] = existing.copy(
                name = name ?: existing.name,
                reason = reason,
                retryCount = retryCount,
                lastFailedAt = now,
                permanentlyFailed = retryCount >= 3,
            )
        } else {
            next.add(
                RetryItem(
                    tsCode = tsCode,
                    name = name,
                    reason = reason,
                    retryCount = 1,
                    lastFailedAt = now,
                    permanentlyFailed = false,
                ),
            )
        }
        writeRetryQueue(next)
    }

    fun removeRetryItem(tsCode: String) {
        writeRetryQueue(readRetryQueue().filterNot { it.tsCode == tsCode })
    }

    /** 删除 `filesDir/cache/daily/` 下所有 `.json` 缓存，返回成功删除的文件数 */
    fun clearAllDailyJsonFiles(): Int {
        if (!root.exists()) return 0
        var n = 0
        root.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".json", ignoreCase = true) && f.delete()) {
                n++
            }
        }
        return n
    }

    private fun FinancialStatementData.latestReportDate(): String? {
        return listOfNotNull(
            balanceSheets.maxOfOrNull { it.endDate },
            cashflows.maxOfOrNull { it.endDate },
            incomes.maxOfOrNull { it.endDate },
            finaIndicators.maxOfOrNull { it.endDate },
        ).maxOrNull()
    }
}
