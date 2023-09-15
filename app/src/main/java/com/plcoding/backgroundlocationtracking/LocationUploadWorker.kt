package com.plcoding.backgroundlocationtracking

//import android.content.Context
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class LocationUploadWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        // Perform your background task here
        // For example, upload location data to server

        // Return Result.success() if the work is successful
        return Result.success()
    }
}