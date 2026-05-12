package com.msai.longtermstockpicker.domain

data class OwnershipInfo(
    val tsCode: String,
    val name: String,
    val companyType: String,
    val ownershipScore: Double,
    val source: String,
    val remark: String,
) {
    companion object {
        fun unknown(tsCode: String): OwnershipInfo = OwnershipInfo(
            tsCode = tsCode,
            name = "",
            companyType = OwnershipTier.UNKNOWN.displayName,
            ownershipScore = OwnershipTier.UNKNOWN.score,
            source = "default",
            remark = "CSV未配置，使用默认分",
        )
    }
}
