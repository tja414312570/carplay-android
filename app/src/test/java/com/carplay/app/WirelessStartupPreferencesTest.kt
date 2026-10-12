package com.carplay.app

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class WirelessStartupPreferencesTest {
    @Test fun upgradeReplacesSavedExperimentChoicesWithoutChangingIdentityOrOtherSettings() {
        val initial = linkedMapOf<String, Any>(
            "connectionDirection" to "VEHICLE_INITIATED",
            "bootstrapProfile" to "VIVO_WIRELESS_MINIMAL",
            "phoneBluetoothAddress" to "12:34:56:78:9A:BC",
            "peer" to "isolated-test-peer",
            "identity" to "isolated-sealed-identity",
            "accessoryKey:isolated-test-peer" to "isolated-sealed-accessory-key",
            "pair:isolated-test-peer" to "isolated-sealed-pair",
            "requireRoot" to true,
            "autoUpload" to false,
            "server" to "https://test.invalid",
            "bluetoothProfile" to "VIVO_ACCESSORY_FIRST",
        )
        val store = Preferences(initial)
        WirelessStartupPreferences.upgrade(store.prefs, component = true)
        val expected = initial + mapOf(
            "connectionDirection" to "PHONE_INITIATED",
            "bootstrapProfile" to "MODERN_WIRELESS",
            "wirelessStartupBaselineRevision" to 19,
        )
        assertEquals(expected, store.values)
        assertEquals(1, store.commits)
    }

    @Test fun newComponentInstallationUsesTheSamePhoneInitiatedBaseline() {
        val store = Preferences()
        WirelessStartupPreferences.upgrade(store.prefs, component = true)
        assertEquals("PHONE_INITIATED", store.values["connectionDirection"])
        assertEquals("MODERN_WIRELESS", store.values["bootstrapProfile"])
        assertEquals(19, store.values["wirelessStartupBaselineRevision"])
    }

    @Test fun laterExplicitChoicesAreNotOverwrittenOnEveryServiceOrActivityCreation() {
        val store = Preferences(mapOf("bootstrapProfile" to "VIVO_FLAT_WIFI_REQUEST"))
        WirelessStartupPreferences.upgrade(store.prefs, component = true)
        store.prefs.edit().putString("connectionDirection", "VEHICLE_INITIATED")
            .putString("bootstrapProfile", "VIVO_WIRELESS_MINIMAL").commit()
        val selected = store.values.toMap()
        val commits = store.commits
        repeat(3) { WirelessStartupPreferences.upgrade(store.prefs, component = true) }
        assertEquals(selected, store.values)
        assertEquals(commits, store.commits)
    }

    @Test fun standalonePreferencesDoNotReceiveTheComponentCompatibilityUpgrade() {
        val initial = mapOf<String, Any>("bootstrapProfile" to "GENERIC", "connectionDirection" to "PHONE_INITIATED")
        val store = Preferences(initial)
        WirelessStartupPreferences.upgrade(store.prefs, component = false)
        assertEquals(initial, store.values)
        assertEquals(0, store.commits)
    }

    @Test fun aNewerBaselineIsNotDowngraded() {
        val initial = mapOf<String, Any>("wirelessStartupBaselineRevision" to 20,
            "bootstrapProfile" to "VIVO_FLAT_REFERENCE", "connectionDirection" to "VEHICLE_INITIATED")
        val store = Preferences(initial)
        WirelessStartupPreferences.upgrade(store.prefs, component = true)
        assertEquals(initial, store.values)
        assertEquals(0, store.commits)
    }

    @Test fun persistenceFailureIsReportedInsteadOfPretendingTheUpgradeWasSaved() {
        val store = Preferences(commitSucceeds = false)
        val failure = assertThrows(IllegalStateException::class.java) {
            WirelessStartupPreferences.upgrade(store.prefs, component = true)
        }
        assertTrue(failure.message!!.contains("保存手机主动连接设置失败"))
    }

    /** Only the preferences API exercised here is implemented; unrelated calls fail loudly. */
    private class Preferences(initial: Map<String, Any> = emptyMap(), private val commitSucceeds: Boolean = true) {
        val values = initial.toMutableMap()
        var commits = 0
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getInt" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any>()
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putString", "putInt" -> {
                        pending[args!![0] as String] = checkNotNull(args[1])
                        editor
                    }
                    "commit" -> {
                        commits++
                        if (commitSucceeds) values.putAll(pending)
                        commitSucceeds
                    }
                    else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
                }
            } as SharedPreferences.Editor
            return editor
        }
    }
}
