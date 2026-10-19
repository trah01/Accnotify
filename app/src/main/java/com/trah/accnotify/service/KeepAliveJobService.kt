package com.trah.accnotify.service

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * JobService for keeping the WebSocket service alive.
 * This provides a backup mechanism in case the foreground service is killed.
 */
class KeepAliveJobService : JobService() {

    companion object {
        private const val TAG = "KeepAliveJobService"
        private const val JOB_ID = 1001
        private const val INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

        /**
         * Schedule the keep-alive job
         */
        fun schedule(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            
            // Check if job is already scheduled
            val pendingJob = jobScheduler.getPendingJob(JOB_ID)
            if (pendingJob != null) {
                Log.d(TAG, "Keep-alive job already scheduled")
                return
            }

            val componentName = ComponentName(context, KeepAliveJobService::class.java)
            
            val jobInfoBuilder = JobInfo.Builder(JOB_ID, componentName)
                .setPersisted(true) // Survive reboots
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // On Android 7.0+, minimum periodic interval is 15 minutes
                jobInfoBuilder.setPeriodic(INTERVAL_MS, INTERVAL_MS / 3)
            } else {
                jobInfoBuilder.setPeriodic(INTERVAL_MS)
            }

            val result = jobScheduler.schedule(jobInfoBuilder.build())
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.i(TAG, "Keep-alive job scheduled successfully")
            } else {
                Log.e(TAG, "Failed to schedule keep-alive job")
            }
        }

        /**
         * Cancel the keep-alive job
         */
        fun cancel(context: Context) {
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.cancel(JOB_ID)
            Log.i(TAG, "Keep-alive job cancelled")
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "Keep-alive job started")

        val isRegistered = try {
            com.trah.accnotify.AccnotifyApp.getInstance().keyManager.isRegistered
        } catch (_: Exception) {
            false
        }

        if (!isRegistered) {
            Log.i(TAG, "Skip keep-alive start: device not registered")
            return false
        }
        
        // Start WebSocket service
        try {
            WebSocketService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WebSocket service from job", e)
        }
        
        // Job is complete
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.w(TAG, "Keep-alive job stopped")
        // Reschedule the job
        return true
    }
}
