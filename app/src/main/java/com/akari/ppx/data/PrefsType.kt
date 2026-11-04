package com.akari.ppx.data

enum class PrefsType(val method: String, val key: String) {
    STRING("s", "s"),
    BOOLEAN("b", "b"),
    SET_STRING("ss", "s"),
    SET_BOOLEAN("sb", "b"),
}
