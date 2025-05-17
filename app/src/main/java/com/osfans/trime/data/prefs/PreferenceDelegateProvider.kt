/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data.prefs

import androidx.preference.PreferenceScreen

abstract class PreferenceDelegateProvider {
    private val _preferenceDelegates: MutableMap<String, PreferenceDelegate<*>> = mutableMapOf()

    private val _preferenceDelegatesUi: MutableList<PreferenceDelegateUi<*>> = mutableListOf()

    val preferenceDelegates: Map<String, PreferenceDelegate<*>>
        get() = _preferenceDelegates

    val preferenceDelegatesUi: List<PreferenceDelegateUi<*>>
        get() = _preferenceDelegatesUi

    open fun createUi(screen: PreferenceScreen) {
    }

    fun PreferenceDelegateUi<*>.registerUi() {
        _preferenceDelegatesUi.add(this)
    }

    fun PreferenceDelegate<*>.register() {
        _preferenceDelegates[key] = this
    }
}
