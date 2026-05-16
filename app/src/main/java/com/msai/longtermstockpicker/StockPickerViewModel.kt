package com.msai.longtermstockpicker

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.msai.longtermstockpicker.data.MobileResultPack
import com.msai.longtermstockpicker.data.StrategyLogicFile
import com.msai.longtermstockpicker.data.StrategyLogicSection
import com.msai.longtermstockpicker.data.db.DatabaseProvider
import com.msai.longtermstockpicker.data.db.ImportMetaEntity
import com.msai.longtermstockpicker.data.db.WatchlistEntity
import com.msai.longtermstockpicker.data.db.toScoreResult
import com.msai.longtermstockpicker.data.toEntity
import com.msai.longtermstockpicker.data.toImportMeta
import com.msai.longtermstockpicker.domain.ScoreResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class ViewerStatus(
    val latestTradeDate: String? = null,
    val importedCount: Int = 0,
    val modelVersion: String? = null,
    val generatedAt: String? = null,
    val importedAt: Long? = null,
    val lastSyncAt: Long? = null,
)

data class ImportResult(
    val generatedAt: String,
    val tradeDate: String,
    val importedCount: Int,
    val modelVersion: String,
)

data class SyncStatus(
    val message: String = "",
    val isLoading: Boolean = false,
    val importedCount: Int? = null,
    val tradeDate: String? = null,
    val modelVersion: String? = null,
)

data class WatchlistRow(
    val tsCode: String,
    val name: String?,
    val industry: String?,
    val addedAt: Long,
    val latestTradeDate: String?,
    val latestScore: Double?,
    val latestSignalLevel: String?,
)

data class StrategyLogicState(
    val generatedAt: String? = null,
    val tradeDate: String? = null,
    val modelVersion: String? = null,
    val syncedAt: Long? = null,
    val sections: List<StrategyLogicSection> = emptyList(),
    val errorMessage: String? = null,
)

class StockPickerViewModel(app: Application) : AndroidViewModel(app) {
    private val db = DatabaseProvider.get(app.applicationContext)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val prefs = app.getSharedPreferences("viewer_prefs", Application.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _results = MutableStateFlow<List<ScoreResult>>(emptyList())
    val results: StateFlow<List<ScoreResult>> = _results.asStateFlow()

    private val _viewerStatus = MutableStateFlow(ViewerStatus())
    val viewerStatus: StateFlow<ViewerStatus> = _viewerStatus.asStateFlow()

    private val _lastImportResult = MutableStateFlow<ImportResult?>(null)
    val lastImportResult: StateFlow<ImportResult?> = _lastImportResult.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _syncStatus = MutableStateFlow(SyncStatus())
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    val resultUrl: String = DEFAULT_RESULT_URL

    private val _watchlistCodes = MutableStateFlow<Set<String>>(emptySet())
    val watchlistCodes: StateFlow<Set<String>> = _watchlistCodes.asStateFlow()

    private val _watchlistRows = MutableStateFlow<List<WatchlistRow>>(emptyList())
    val watchlistRows: StateFlow<List<WatchlistRow>> = _watchlistRows.asStateFlow()

    private val _strategyLogic = MutableStateFlow(StrategyLogicState())
    val strategyLogic: StateFlow<StrategyLogicState> = _strategyLogic.asStateFlow()

    private val _navigateToResults = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToResults: SharedFlow<Unit> = _navigateToResults.asSharedFlow()

    init {
        refreshStatus()
        refreshWatchlist()
        loadCachedStrategyLogic()
        loadLatestRanking(100, navigate = false)
    }

    fun clearError() {
        _lastError.value = null
    }

    fun refreshStatus() {
        viewModelScope.launch {
            val meta = db.importMetaDao().getLatest()
            val latestDate = db.scoreResultDao().getLatestScoreDate()
            val count = latestDate?.let { db.scoreResultDao().countByTradeDate(it) } ?: 0
            _viewerStatus.value = ViewerStatus(
                latestTradeDate = latestDate ?: meta?.tradeDate,
                importedCount = count,
                modelVersion = meta?.modelVersion,
                generatedAt = meta?.generatedAt,
                importedAt = meta?.importedAt,
                lastSyncAt = prefs.getLong(KEY_LAST_SYNC_AT, 0L).takeIf { it > 0L },
            )
        }
    }

    fun loadLatestRanking(limit: Int = 100, navigate: Boolean = true) {
        viewModelScope.launch {
            val rows = db.scoreResultDao().getTopScoresByLatestTradeDate(limit).map { it.toScoreResult() }
            _results.value = rows
            refreshStatus()
            if (navigate) {
                if (rows.isEmpty()) _lastError.value = "暂无评分结果，请先同步云端结果" else _navigateToResults.tryEmit(Unit)
            }
        }
    }

    fun syncComputerResults() {
        viewModelScope.launch {
            val url = DEFAULT_RESULT_URL
            _syncStatus.value = SyncStatus(message = "正在下载", isLoading = true)
            runCatching {
                val bytes = downloadBytes(url)
                val result = importBytes(bytes)
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putLong(KEY_LAST_SYNC_AT, now)
                    .apply()
                _syncStatus.value = SyncStatus(
                    message = "下载成功",
                    importedCount = result.importedCount,
                    tradeDate = result.tradeDate,
                    modelVersion = result.modelVersion,
                )
                syncStrategyLogic(now)
                refreshStatus()
                refreshWatchlist()
            }.onFailure { e ->
                _syncStatus.value = SyncStatus(
                    message = syncErrorMessage(e),
                    isLoading = false,
                )
            }
        }
    }

    private suspend fun syncStrategyLogic(syncedAt: Long) {
        runCatching {
            val text = downloadBytes(DEFAULT_STRATEGY_LOGIC_URL).toString(Charsets.UTF_8)
            val logic = json.decodeFromString(StrategyLogicFile.serializer(), text)
            prefs.edit()
                .putString(KEY_STRATEGY_LOGIC_JSON, text)
                .putLong(KEY_STRATEGY_LOGIC_SYNC_AT, syncedAt)
                .apply()
            _strategyLogic.value = logic.toState(syncedAt)
        }.onFailure { e ->
            _strategyLogic.value = _strategyLogic.value.copy(
                errorMessage = "选股逻辑同步失败：${e.message ?: e::class.java.simpleName}",
            )
        }
    }

    private fun loadCachedStrategyLogic() {
        val text = prefs.getString(KEY_STRATEGY_LOGIC_JSON, null).orEmpty()
        val syncedAt = prefs.getLong(KEY_STRATEGY_LOGIC_SYNC_AT, 0L).takeIf { it > 0L }
        if (text.isBlank()) return
        runCatching {
            json.decodeFromString(StrategyLogicFile.serializer(), text)
        }.onSuccess {
            _strategyLogic.value = it.toState(syncedAt)
        }.onFailure { e ->
            _strategyLogic.value = StrategyLogicState(errorMessage = "本地选股逻辑读取失败：${e.message ?: e::class.java.simpleName}")
        }
    }

    fun clearLocalResults() {
        viewModelScope.launch {
            db.scoreResultDao().deleteAll()
            db.importMetaDao().deleteAll()
            _results.value = emptyList()
            _lastImportResult.value = null
            refreshStatus()
            refreshWatchlist()
        }
    }

    fun refreshWatchlist() {
        viewModelScope.launch {
            val items = db.watchlistDao().getAll()
            _watchlistCodes.value = items.map { it.tsCode }.toSet()
            _watchlistRows.value = items.map { item ->
                val score = db.scoreResultDao().getLatestByTsCode(item.tsCode)
                WatchlistRow(
                    tsCode = item.tsCode,
                    name = score?.name ?: item.name,
                    industry = score?.industry ?: item.industry,
                    addedAt = item.addedAt,
                    latestTradeDate = score?.tradeDate,
                    latestScore = score?.totalScore,
                    latestSignalLevel = score?.signalLevel,
                )
            }
        }
    }

    fun addToWatchlist(item: ScoreResult) {
        viewModelScope.launch {
            db.watchlistDao().upsert(
                WatchlistEntity(
                    tsCode = item.tsCode,
                    name = item.stockName,
                    industry = item.industry,
                    addedAt = System.currentTimeMillis(),
                ),
            )
            refreshWatchlist()
        }
    }

    fun removeFromWatchlist(tsCode: String) {
        viewModelScope.launch {
            db.watchlistDao().deleteByTsCode(tsCode)
            refreshWatchlist()
        }
    }

    fun clearWatchlist() {
        viewModelScope.launch {
            db.watchlistDao().deleteAll()
            refreshWatchlist()
        }
    }

    private suspend fun downloadBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.bytes() ?: error("响应为空")
        }
    }

    private suspend fun importBytes(bytes: ByteArray): ImportResult {
        val text = readLatestScoreJson(bytes)
        val pack = json.decodeFromString(MobileResultPack.serializer(), text)
        if (pack.results.isEmpty()) error("结果文件中 results 为空")
        val now = System.currentTimeMillis()
        db.scoreResultDao().deleteByTradeDate(pack.tradeDate)
        db.scoreResultDao().upsertAll(pack.results.map { it.toEntity(now) })
        db.importMetaDao().upsert(pack.toImportMeta(now))
        val result = ImportResult(
            generatedAt = pack.generatedAt,
            tradeDate = pack.tradeDate,
            importedCount = pack.results.size,
            modelVersion = pack.modelVersion,
        )
        _lastImportResult.value = result
        loadLatestRanking(100, navigate = false)
        refreshStatus()
        refreshWatchlist()
        return result
    }

    private fun readLatestScoreJson(bytes: ByteArray): String {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.substringAfterLast('/') == "latest_score.json") {
                        return zip.readBytes().toString(Charsets.UTF_8)
                    }
                }
            }
            error("ZIP 中未找到 latest_score.json")
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun syncErrorMessage(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("timeout", ignoreCase = true) ||
                raw.contains("failed to connect", ignoreCase = true) ||
                raw.contains("connection reset", ignoreCase = true) -> {
                "连接失败：请确认云端结果服务已启动，手机网络可访问服务地址，并已放行 8765 端口。"
            }
            raw.startsWith("HTTP") -> "下载失败：云端结果服务返回 $raw。"
            raw.contains("latest_score.json") -> "导入失败：结果包中没有 latest_score.json。"
            else -> "同步失败：${raw.ifBlank { error::class.java.simpleName }}"
        }
    }

    private companion object {
        const val DEFAULT_RESULT_URL = "https://raw.githubusercontent.com/ms405755994-ops/LongTermStockPicker/main/docs/results/latest_score_top100.json"
        const val DEFAULT_STRATEGY_LOGIC_URL = "https://raw.githubusercontent.com/ms405755994-ops/LongTermStockPicker/main/docs/results/strategy_logic.json"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_STRATEGY_LOGIC_JSON = "strategy_logic_json"
        const val KEY_STRATEGY_LOGIC_SYNC_AT = "strategy_logic_sync_at"
    }
}

private fun StrategyLogicFile.toState(syncedAt: Long?): StrategyLogicState = StrategyLogicState(
    generatedAt = generatedAt.takeIf { it.isNotBlank() },
    tradeDate = tradeDate.takeIf { it.isNotBlank() },
    modelVersion = modelVersion.takeIf { it.isNotBlank() },
    syncedAt = syncedAt,
    sections = sections,
)
