package com.example.lab2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SMS_PERMISSION_CODE = 101
        private const val UNIQUE_WORK_NAME = "spyJob"
        private const val TAG_WORK = "SPYJOB"
    }

    private lateinit var textView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.textView)

        findViewById<Button>(R.id.start_button).setOnClickListener {
            if (hasSmsPermission()) {
                startTask()
                textView.text = "Service is running"
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_SMS),
                    SMS_PERMISSION_CODE
                )
            }
        }

        findViewById<Button>(R.id.stop_button).setOnClickListener {
            stopTask()
            textView.text = "Service is not running"
        }
        findViewById<Button>(R.id.debug_button).setOnClickListener {
            if (hasSmsPermission()) {
                runOnceTask()
                textView.text = "Debug: one-time task enqueued"
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_SMS),
                    SMS_PERMISSION_CODE
                )
            }
        }
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startTask()
            textView.text = "Service is running"
        } else {
            textView.text = "Permission denied"
        }
    }

    private fun startTask() {
        // Ограничения: сеть доступна (любой тип)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // Периодическая задача (минимум 15 минут — ограничение системы)
        val request = PeriodicWorkRequestBuilder<SmsWorker>(
            15, TimeUnit.MINUTES
        )
            .addTag(TAG_WORK)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }

    private fun stopTask() {
        WorkManager.getInstance(applicationContext)
            .cancelAllWorkByTag(TAG_WORK)
    }
    private fun runOnceTask() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SmsWorker>()
            .addTag(TAG_WORK)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(applicationContext).enqueue(request)
    }
}