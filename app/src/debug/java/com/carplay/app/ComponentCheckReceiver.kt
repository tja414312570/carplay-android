package com.carplay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carplay.app.diagnostics.Diagnostics

/** Shell-authorized, debug-only integration check; no display or transport session is created. */
class ComponentCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.carplay.CHECK_COMPONENT") return
        Diagnostics.initialize(context)
        val pending = goAsync()
        try { ComponentInterfaceCheck.run(context.applicationContext) { pending.finish() } }
        catch (error: Exception) { Diagnostics.event("WARN",error.message ?: "接口检查未启动"); pending.finish() }
    }
}
