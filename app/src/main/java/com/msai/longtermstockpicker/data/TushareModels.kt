package com.msai.longtermstockpicker.data

import com.msai.longtermstockpicker.domain.Quote
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class TushareDailyRequest(
    @SerialName("api_name") val apiName: String = "daily",
    val token: String,
    val params: DailyParams,
    val fields: String,
)

@Serializable
data class TushareStockBasicRequest(
    @SerialName("api_name") val apiName: String = "stock_basic",
    val token: String,
    val params: StockBasicParams = StockBasicParams(),
    val fields: String,
)

@Serializable
data class TushareFinancialRequest(
    @SerialName("api_name") val apiName: String,
    val token: String,
    val params: TsCodeParams,
    val fields: String,
)

@Serializable
data class DailyParams(
    @SerialName("ts_code") val tsCode: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
)

@Serializable
data class StockBasicParams(
    @SerialName("list_status") val listStatus: String = "L",
)

@Serializable
data class TsCodeParams(
    @SerialName("ts_code") val tsCode: String,
)

@Serializable
data class StockBasicInfo(
    @SerialName("ts_code") val tsCode: String,
    val name: String = "",
    val industry: String = "",
    @SerialName("list_date") val listDate: String = "",
    val market: String = "",
)

@Serializable
data class BalanceSheetRow(
    @SerialName("end_date") val endDate: String = "",
    @SerialName("money_cap") val moneyCap: Double? = null,
    @SerialName("st_borr") val shortBorrow: Double? = null,
    @SerialName("non_cur_liab_due_1y") val nonCurrentLiabDue1y: Double? = null,
    @SerialName("lt_borr") val longBorrow: Double? = null,
    @SerialName("bond_payable") val bondPayable: Double? = null,
    @SerialName("total_assets") val totalAssets: Double? = null,
    @SerialName("total_liab") val totalLiab: Double? = null,
)

@Serializable
data class CashflowRow(
    @SerialName("end_date") val endDate: String = "",
    @SerialName("n_cashflow_act") val netCashflowAct: Double? = null,
)

@Serializable
data class IncomeRow(
    @SerialName("end_date") val endDate: String = "",
    @SerialName("n_income") val netIncome: Double? = null,
)

@Serializable
data class FinaIndicatorRow(
    @SerialName("end_date") val endDate: String = "",
    @SerialName("debt_to_assets") val debtToAssets: Double? = null,
)

@Serializable
data class FinancialStatementData(
    val balanceSheets: List<BalanceSheetRow> = emptyList(),
    val cashflows: List<CashflowRow> = emptyList(),
    val incomes: List<IncomeRow> = emptyList(),
    val finaIndicators: List<FinaIndicatorRow> = emptyList(),
)

fun buildDailyRequestJson(token: String, tsCode: String, startDate: String, endDate: String): String {
    val req = TushareDailyRequest(
        token = token,
        params = DailyParams(tsCode = tsCode, startDate = startDate, endDate = endDate),
        fields = "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount",
    )
    return json.encodeToString(req)
}

fun buildStockBasicRequestJson(token: String): String {
    val req = TushareStockBasicRequest(
        token = token,
        fields = "ts_code,name,industry,list_date,market",
    )
    return json.encodeToString(req)
}

fun buildFinancialRequestJson(token: String, apiName: String, tsCode: String, fields: String): String {
    val req = TushareFinancialRequest(
        apiName = apiName,
        token = token,
        params = TsCodeParams(tsCode),
        fields = fields,
    )
    return json.encodeToString(req)
}

@Serializable
data class TushareEnvelope(
    val code: Int,
    val msg: String? = null,
    val data: TushareTable? = null,
)

@Serializable
data class TushareTable(
    val fields: List<String>? = null,
    val items: List<JsonArray>? = null,
)

fun parseTushareEnvelope(body: String): TushareEnvelope = json.decodeFromString(body)

fun parseDailyQuotes(envelope: TushareEnvelope): List<Quote> {
    val table = envelope.data ?: return emptyList()
    val fields = table.fields ?: return emptyList()
    val items = table.items ?: return emptyList()
    val idx = fields.withIndex().associate { it.value to it.index }
    fun ix(name: String): Int = idx[name] ?: error("missing field $name")

    val iTs = ix("ts_code")
    val iDate = ix("trade_date")
    val iOpen = ix("open")
    val iHigh = ix("high")
    val iLow = ix("low")
    val iClose = ix("close")
    val iPre = idx["pre_close"]
    val iChg = idx["change"]
    val iPct = idx["pct_chg"]
    val iVol = ix("vol")
    val iAmt = ix("amount")

    return items.map { row ->
        fun num(i: Int): Double = row[i].primitiveDouble()
        fun numOpt(i: Int?): Double? = i?.let { row[it].primitiveDoubleOrNull() }

        Quote(
            tsCode = row[iTs].jsonPrimitive.content,
            tradeDate = row[iDate].jsonPrimitive.content,
            open = num(iOpen),
            high = num(iHigh),
            low = num(iLow),
            close = num(iClose),
            preClose = numOpt(iPre),
            change = numOpt(iChg),
            pctChg = numOpt(iPct),
            vol = num(iVol),
            amount = num(iAmt),
        )
    }
}

fun parseStockBasic(envelope: TushareEnvelope): List<StockBasicInfo> {
    val table = envelope.data ?: return emptyList()
    val fields = table.fields ?: return emptyList()
    val items = table.items ?: return emptyList()
    val idx = fields.withIndex().associate { it.value to it.index }
    fun value(row: JsonArray, name: String): String {
        val i = idx[name] ?: return ""
        return row.getOrNull(i)?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }

    return items.map { row ->
        StockBasicInfo(
            tsCode = value(row, "ts_code"),
            name = value(row, "name"),
            industry = value(row, "industry"),
            listDate = value(row, "list_date"),
            market = value(row, "market"),
        )
    }.filter { it.tsCode.isNotBlank() }
}

fun parseBalanceSheet(envelope: TushareEnvelope): List<BalanceSheetRow> {
    return parseRows(envelope) { row, value, num ->
        BalanceSheetRow(
            endDate = value(row, "end_date"),
            moneyCap = num(row, "money_cap"),
            shortBorrow = num(row, "st_borr"),
            nonCurrentLiabDue1y = num(row, "non_cur_liab_due_1y"),
            longBorrow = num(row, "lt_borr"),
            bondPayable = num(row, "bond_payable"),
            totalAssets = num(row, "total_assets"),
            totalLiab = num(row, "total_liab"),
        )
    }
}

fun parseCashflow(envelope: TushareEnvelope): List<CashflowRow> {
    return parseRows(envelope) { row, value, num ->
        CashflowRow(
            endDate = value(row, "end_date"),
            netCashflowAct = num(row, "n_cashflow_act"),
        )
    }
}

fun parseIncome(envelope: TushareEnvelope): List<IncomeRow> {
    return parseRows(envelope) { row, value, num ->
        IncomeRow(
            endDate = value(row, "end_date"),
            netIncome = num(row, "n_income"),
        )
    }
}

fun parseFinaIndicator(envelope: TushareEnvelope): List<FinaIndicatorRow> {
    return parseRows(envelope) { row, value, num ->
        FinaIndicatorRow(
            endDate = value(row, "end_date"),
            debtToAssets = num(row, "debt_to_assets"),
        )
    }
}

private fun <T> parseRows(
    envelope: TushareEnvelope,
    mapper: (JsonArray, (JsonArray, String) -> String, (JsonArray, String) -> Double?) -> T,
): List<T> {
    val table = envelope.data ?: return emptyList()
    val fields = table.fields ?: return emptyList()
    val items = table.items ?: return emptyList()
    val idx = fields.withIndex().associate { it.value to it.index }
    fun value(row: JsonArray, name: String): String {
        val i = idx[name] ?: return ""
        return row.getOrNull(i)?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    }
    fun num(row: JsonArray, name: String): Double? {
        val i = idx[name] ?: return null
        return row.getOrNull(i)?.primitiveDoubleOrNull()
    }
    return items.map { mapper(it, ::value, ::num) }
}

private fun JsonElement.primitiveDouble(): Double = when (this) {
    is JsonPrimitive -> this.doubleOrNull ?: this.content.toDouble()
    else -> error("not a primitive")
}

private fun JsonElement.primitiveDoubleOrNull(): Double? = when (this) {
    is JsonPrimitive -> this.doubleOrNull ?: this.contentOrNull?.toDoubleOrNull()
    else -> null
}
