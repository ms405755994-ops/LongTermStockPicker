package com.msai.longtermstockpicker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StrategyLogicFile(
    @SerialName("generated_at") val generatedAt: String = "",
    @SerialName("trade_date") val tradeDate: String = "",
    @SerialName("model_version") val modelVersion: String = "",
    val sections: List<StrategyLogicSection> = emptyList(),
)

@Serializable
data class StrategyLogicSection(
    val title: String = "",
    val body: String = "",
    val emphasized: Boolean = false,
    val kind: String = "text",
)
