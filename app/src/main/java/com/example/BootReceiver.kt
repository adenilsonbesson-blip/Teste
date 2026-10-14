package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || 
            intent.action == "android.intent.action.QUICKBOOT_POWERON" || 
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                DriveBackupWorker.scheduleOrCancel(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                try {
                    context.startActivity(launchIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
