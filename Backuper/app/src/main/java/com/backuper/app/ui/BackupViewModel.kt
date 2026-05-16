package com.backuper.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.work.*
import com.backuper.app.security.EncryptionManager
import com.backuper.app.worker.UploadWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)
    
    private val _token = MutableStateFlow(EncryptionManager.getToken(application) ?: "")
    val token: StateFlow<String> = _token

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    private val _progressData = MutableStateFlow<WorkInfo?>(null)
    val progressData: StateFlow<WorkInfo?> = _progressData

    private var currentWorkId: UUID? = null

    fun onTokenChange(newToken: String) {
        _token.value = newToken
        EncryptionManager.saveToken(getApplication(), newToken)
    }

    fun startBackup(wifiOnly: Boolean) {
        if (_token.value.isBlank()) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        currentWorkId = uploadRequest.id
        workManager.enqueueUniqueWork("backup_job", ExistingWorkPolicy.REPLACE, uploadRequest)
        _isUploading.value = true

        // Observe progress
        workManager.getWorkInfoByIdLiveData(uploadRequest.id).asFlow().let { flow ->
            // In a real app, collect this in a scope
        }
    }

    fun stopBackup() {
        workManager.cancelUniqueWork("backup_job")
        _isUploading.value = false
    }
}
