package com.wifi.toolbox.utils

import androidx.core.os.LocaleListCompat

object LocaleConfigs {
    data class LocaleItem(
        val tag: String,
        val displayName: String,
        val show: Boolean = true
    )

    val list = listOf(
        LocaleItem("", "Follow system"),
        LocaleItem("en", "English"),
        LocaleItem("ar", "العربية")
    )

    fun getLocaleListCompat(index: Int): LocaleListCompat {
        val item = list.getOrNull(index) ?: list[0]
        return if (item.tag.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(item.tag)
        }
    }
}