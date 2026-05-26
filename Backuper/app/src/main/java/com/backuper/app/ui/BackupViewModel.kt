package com.backuper.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.backuper.app.security.EncryptionManager
import com.backuper.app.worker.UploadWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val workManager = WorkManager.getInstance(application)
    
    private val _token = MutableStateFlow(EncryptionManager.getToken(application) ?: "")
    val token: StateFlow<String> = _token

    private val _isUploading = MutableStateFlow(false)
    val isUploading: StateFlow<Boolean> = _isUploading

    private val _foundCount = MutableStateFlow(0)
    val foundCount: StateFlow<Int> = _foundCount

    private val _uploadedCount = MutableStateFlow(0)
    val uploadedCount: StateFlow<Int> = _uploadedCount

    private val _currentFile = MutableStateFlow("")
    val currentFile: StateFlow<String> = _currentFile

    private var observationJob: Job? = null

    init {
        // Automatically check if an active job is already running
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkLiveData("backup_job").asFlow().collect { workInfoList ->
                val activeInfo = workInfoList.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                if (activeInfo != null) {
                    _isUploading.value = true
                    observeProgress(activeInfo.id)
                } else {
                    _isUploading.value = false
                }
            }
        }
    }

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

        workManager.enqueueUniqueWork("backup_job", ExistingWorkPolicy.REPLACE, uploadRequest)
        _isUploading.value = true
        observeProgress(uploadRequest.id)
    }

    private fun observeProgress(workId: java.util.UUID) {
        observationJob?.cancel()
        observationJob = viewModelScope.launch {
            workManager.getWorkInfoByIdLiveData(workId).asFlow().collect { workInfo ->
                if (workInfo != null) {
                    val progress = workInfo.progress
                    _foundCount.value = progress.getInt(UploadWorker.KEY_FOUND_COUNT, _foundCount.value)
                    _uploadedCount.value = progress.getInt(UploadWorker.KEY_UPLOADED_COUNT, _uploadedCount.value)
                    _currentFile.value = progress.getString(UploadWorker.KEY_CURRENT_FILE) ?: _currentFile.value

                    if (workInfo.state.isFinished) {
                        _isUploading.value = false
                    }
                }
            }
        }
    }

    fun stopBackup() {
        workManager.cancelUniqueWork("backup_job")
        _isUploading.value = false
        observationJob?.cancel()
    }
}
