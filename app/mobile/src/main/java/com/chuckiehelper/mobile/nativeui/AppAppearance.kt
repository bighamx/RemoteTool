package com.chuckiehelper.mobile.nativeui

internal enum class AppAppearance(val value: String, val label: String) {
    LIGHT("light", "浅色"), DARK("dark", "深色"), SYSTEM("system", "跟随系统");

    companion object {
        const val KEY = "appearance_mode"
        fun from(value: String?) = entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}
