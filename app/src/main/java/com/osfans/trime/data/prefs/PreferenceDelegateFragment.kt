/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data.prefs

import android.os.Bundle
import androidx.annotation.CallSuper
import androidx.preference.PreferenceScreen
import com.osfans.trime.ui.components.PaddingPreferenceFragment

abstract class PreferenceDelegateFragment(
    private val preferenceProvider: PreferenceDelegateProvider,
) : PaddingPreferenceFragment() {
    open fun onPreferenceUiCreated(screen: PreferenceScreen) {}

    @CallSuper
    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        preferenceScreen =
            preferenceManager.createPreferenceScreen(preferenceManager.context).also { screen ->
                preferenceProvider.createUi(screen)
                onPreferenceUiCreated(screen)
            }
    }
}
