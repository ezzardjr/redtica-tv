package com.redtica.camarastv

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings

/** Receptor de administrador del dispositivo (necesario para el modo kiosco "Device Owner"). */
class AdminReceiver : DeviceAdminReceiver()

/**
 * Modo kiosco:
 *  - Nivel 1 (sin nada especial): la app se registra como pantalla de inicio (HOME).
 *    Al elegirla como inicio "Siempre", el TV arranca directo en la app y el botón Home vuelve a ella.
 *  - Nivel 2 (Device Owner por ADB, una sola vez): bloqueo total. No hay menús, ni Home,
 *    ni recientes, ni barra de notificaciones; la app queda fija como inicio.
 */
object Kiosk {
    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun isOwner(ctx: Context) = dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /** Llamar en onResume de la pantalla principal. */
    fun enter(act: Activity) {
        if (!AppState.config.kiosk || !isOwner(act)) return
        val dpm = dpm(act); val admin = admin(act)
        try {
            dpm.setLockTaskPackages(admin, arrayOf(act.packageName))
            val home = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(admin, home, ComponentName(act, MainActivity::class.java))
            if (Build.VERSION.SDK_INT >= 28) dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            dpm.setStatusBarDisabled(admin, true)
            dpm.setKeyguardDisabled(admin, true)
            act.startLockTask()
        } catch (_: Exception) { }
    }

    /** Sale temporalmente del bloqueo y abre Ajustes de Android (mantenimiento). */
    fun maintenance(act: Activity) {
        try { act.stopLockTask() } catch (_: Exception) { }
        if (isOwner(act)) try { dpm(act).setStatusBarDisabled(admin(act), false) } catch (_: Exception) { }
        try {
            act.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    /** Quita el modo kiosco por completo (para poder desinstalar la app o cambiar de TV box). */
    fun removeOwner(act: Activity) {
        if (!isOwner(act)) return
        val dpm = dpm(act); val admin = admin(act)
        try {
            act.stopLockTask()
            dpm.clearPackagePersistentPreferredActivities(admin, act.packageName)
            dpm.setStatusBarDisabled(admin, false)
            dpm.setKeyguardDisabled(admin, false)
            @Suppress("DEPRECATION") dpm.clearDeviceOwnerApp(act.packageName)
        } catch (_: Exception) { }
    }
}
