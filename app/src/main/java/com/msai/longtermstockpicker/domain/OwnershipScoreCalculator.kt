package com.msai.longtermstockpicker.domain

enum class OwnershipTier(val displayName: String, val score: Double) {
    CENTRAL_SOE("中央国资", 100.0),
    PROVINCIAL_SOE("省属国资", 90.0),
    MUNICIPAL_SOE("市属国资/地方国资", 80.0),
    STATE_PARTICIPATION("国资参股", 70.0),
    QUALITY_PRIVATE("优质民企", 75.0),
    NORMAL_PRIVATE("普通民企", 55.0),
    HIGH_RISK_PRIVATE("高负债/高质押民企", 30.0),
    UNKNOWN("未知", 60.0),
}

object OwnershipScoreCalculator {

    private val codeToTier: Map<String, OwnershipTier> = mapOf(
        "600519.SH" to OwnershipTier.CENTRAL_SOE,
        "000001.SZ" to OwnershipTier.MUNICIPAL_SOE,
        "000333.SZ" to OwnershipTier.QUALITY_PRIVATE,
        "601318.SH" to OwnershipTier.QUALITY_PRIVATE,
    )

    fun tierFor(tsCode: String): OwnershipTier = codeToTier[tsCode] ?: OwnershipTier.UNKNOWN

    fun scoreFor(tsCode: String): Double = tierFor(tsCode).score

    fun infoFor(tsCode: String, csvInfo: OwnershipInfo?): OwnershipInfo {
        if (csvInfo != null) return csvInfo
        val tier = tierFor(tsCode)
        return OwnershipInfo(
            tsCode = tsCode,
            name = "",
            companyType = tier.displayName,
            ownershipScore = tier.score,
            source = if (tier == OwnershipTier.UNKNOWN) "default" else "builtin",
            remark = if (tier == OwnershipTier.UNKNOWN) {
                "CSV未配置，使用默认分"
            } else {
                "内置示例映射"
            },
        )
    }
}
