package com.msai.longtermstockpicker.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Quote(
    @SerialName("ts_code") val tsCode: String,
    @SerialName("trade_date") val tradeDate: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    @SerialName("pre_close") val preClose: Double? = null,
    val change: Double? = null,
    @SerialName("pct_chg") val pctChg: Double? = null,
    val vol: Double,
    val amount: Double,
)
