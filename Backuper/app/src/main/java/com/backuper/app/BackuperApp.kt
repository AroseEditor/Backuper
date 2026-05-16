package com.backuper.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class BackuperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()
            
            // Copy to clipboard
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Backuper Crash", stackTrace)
            clipboard.setPrimaryClip(clip)
            
            // Try to show a toast on the main thread
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Crashed! Error copied to clipboard.", Toast.LENGTH_LONG).show()
            }
            
            // Give the toast a second to show before nuking the process
            Thread.sleep(2000)
            
            // Exit
            exitProcess(1)
        }
    }
}
