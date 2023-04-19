package org.fcitx.fcitx5.android.updater

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.updater.api.AndroidJob
import org.fcitx.fcitx5.android.updater.api.CommonApi
import org.fcitx.fcitx5.android.updater.api.JenkinsApi
import org.fcitx.fcitx5.android.updater.network.DownloadEvent
import org.fcitx.fcitx5.android.updater.network.DownloadTask
import java.io.File
import kotlin.math.pow

class VersionViewModel(val androidJob: AndroidJob, initialBuildNumbers: List<Int>) : ViewModel() {

    private var buildNumbers = initialBuildNumbers

    var hasRefreshed = false
        private set

    private val _isRefreshing = MutableStateFlow(false)

    private val remoteVersionUiStates: MutableMap<VersionUi.Remote, MutableStateFlow<RemoteVersionUiState>> =
        mutableMapOf()

    private val remoteDownloadTasks: MutableMap<VersionUi.Remote, DownloadTask?> =
        mutableMapOf()

    val isRefreshing: StateFlow<Boolean>
        get() = _isRefreshing.asStateFlow()

    var installedVersion by mutableStateOf(VersionUi.NotInstalled)
        private set
    private var remoteVersions = mutableMapOf<String, VersionUi.Remote>()
    private val localVersions = mutableMapOf<String, VersionUi.Local>()

    val allVersions = mutableStateMapOf<String, VersionUi>()

    private val VersionUi.isNowInstalled
        get() = installedVersion.versionName == versionName

    private var lastVersionName = ""

    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage = _toastMessage.asSharedFlow()

    private val _fileOperation = MutableSharedFlow<FileOperation>()
    val fileOperation = _fileOperation.asSharedFlow()

    private val downloadDir = File(externalDir, androidJob.jobName).apply {
        mkdirs()
    }

    fun getRemoteUiState(remote: VersionUi.Remote) =
        remoteVersionUiStates.getOrPut(remote) {
            MutableStateFlow(RemoteVersionUiState.Idle(true))
        }.asStateFlow()

    fun uninstall() {
        viewModelScope.launch {
            _fileOperation.emit(FileOperation.Uninstall(androidJob.pkgName))
        }
    }

    fun download(remote: VersionUi.Remote) {
        val flow = remoteVersionUiStates.getValue(remote)
        val task = DownloadTask(remote.downloadUrl, File(downloadDir, remote.versionName + ".apk"))
        var progress = .0f
        task.eventFlow.onEach { event ->
            when (event) {
                DownloadEvent.StartCreating -> {
                    flow.emit(RemoteVersionUiState.Idle(false))
                }
                DownloadEvent.Created -> {
                    flow.emit(RemoteVersionUiState.Pending)
                }
                DownloadEvent.StartPausing -> {
                    flow.emit(RemoteVersionUiState.Downloading(false, progress))
                }
                DownloadEvent.StartResuming -> {
                    flow.emit(RemoteVersionUiState.Pausing(false, progress))
                }
                DownloadEvent.Resumed -> {
                    flow.emit(RemoteVersionUiState.Pending)
                }
                DownloadEvent.Downloaded -> {
                    flow.emit(RemoteVersionUiState.Downloaded)
                    val local = VersionUi.Local(
                        androidJob.pkgName,
                        remote.versionName,
                        remote.size,
                        remote.isNowInstalled,
                        task.file
                    )
                    localVersions[remote.versionName] = local
                    allVersions[remote.versionName] = local
                }
                is DownloadEvent.Failed -> {
                    _toastMessage.emit(event.cause.message ?: event.cause.stackTraceToString())
                }
                DownloadEvent.Paused -> {
                    flow.emit(RemoteVersionUiState.Pausing(true, progress))
                }
                DownloadEvent.Purged -> {
                    flow.emit(RemoteVersionUiState.Idle(true))
                }
                is DownloadEvent.Downloading -> {
                    progress = event.progress.toFloat()
                    flow.emit(RemoteVersionUiState.Downloading(true, progress))
                }
                DownloadEvent.StartPurging -> {
                    flow.emit(RemoteVersionUiState.Downloading(false, progress))
                }
                DownloadEvent.StartWaitingRetry -> {
                    flow.emit(RemoteVersionUiState.WaitingRetry)
                }
            }
        }.let {
            viewModelScope.launch(Dispatchers.Default) {
                it.collect()
            }
        }
        task.start()
        remoteDownloadTasks[remote] = task
    }

    fun pauseDownload(remote: VersionUi.Remote) {
        remoteDownloadTasks[remote]?.pause()
    }


    fun resumeDownload(remote: VersionUi.Remote) {
        remoteDownloadTasks[remote]?.resume()
    }

    fun cancelDownload(remote: VersionUi.Remote) {
        remoteDownloadTasks[remote]?.purge()
    }

    fun getRemoteUrl(local: VersionUi.Local): String? {
        return remoteVersions[local.versionName]?.downloadUrl
    }

    fun delete(local: VersionUi.Local) {
        local.archiveFile.delete()
        val version = local.versionName
        localVersions.remove(version)
        remoteVersions[version]?.let {
            allVersions[version] = it
            viewModelScope.launch {
                remoteVersionUiStates[it]?.emit(RemoteVersionUiState.Idle(true))
            }
        } ?: run {
            allVersions.remove(version)
        }
    }

    fun install(local: VersionUi.Local) {
        viewModelScope.launch {
            _fileOperation.emit(FileOperation.Install(local.archiveFile))
        }
    }

    fun share(local: VersionUi.Local) {
        viewModelScope.launch {
            _fileOperation.emit(FileOperation.Share(local.archiveFile, local.displayName))
        }
    }

    fun export(local: VersionUi.Local) {
        viewModelScope.launch {
            _fileOperation.emit(FileOperation.Export(local.archiveFile, local.displayName))
        }
    }

    fun exportInstalled() {
        val installedPath =
            PackageUtils.getInstalledPath(UpdaterApplication.context, androidJob.pkgName) ?: return
        viewModelScope.launch {
            _fileOperation.emit(
                FileOperation.Export(File(installedPath), installedVersion.displayName)
            )
        }
    }

    init {
        refreshInstalledVersion()
        refreshLocalVersions()
    }

    private fun getInstalled(context: Context) =
        PackageUtils.getInstalledVersionName(context, androidJob.pkgName)
            ?.let { version ->
                PackageUtils.getInstalledSize(context, androidJob.pkgName)
                    ?.let { size ->
                        VersionUi.Installed(androidJob.pkgName, version, size)
                    }

            } ?: VersionUi.NotInstalled

    fun refreshIfInstalledChanged() {
        if (getInstalled(UpdaterApplication.context).versionName != lastVersionName)
            refresh()
    }

    private fun refreshInstalledVersion() {
        installedVersion = getInstalled(UpdaterApplication.context)
        lastVersionName = installedVersion.versionName
    }

    private fun refreshLocalVersions() {
        localVersions.clear()
        downloadDir
            .listFiles { file: File -> file.extension == "apk" }
            ?.mapNotNull {
                PackageUtils.getVersionName(UpdaterApplication.context, it.absolutePath)
                    ?.let { versionName ->
                        VersionUi.Local(
                            androidJob.pkgName,
                            versionName,
                            // Bytes to MiB
                            it.length() / 2.0.pow(20),
                            installedVersion.versionName == versionName,
                            it
                        )
                    }
            }
            ?.forEach {
                localVersions[it.versionName] = it
                allVersions[it.versionName] = it
            }
    }

    fun refresh() {
        if (isRefreshing.value)
            return
        viewModelScope.launch {
            _isRefreshing.emit(true)
            remoteVersions.clear()
            if (hasRefreshed) {
                JenkinsApi.getJobBuilds(androidJob).also {
                    buildNumbers = it.map { b -> b.buildNumber }
                }
            } else {
                JenkinsApi.getJobBuildsByBuildNumbers(androidJob, buildNumbers).also {
                    hasRefreshed = true
                }
            }.mapNotNull {
                it.artifacts.selectByABI()?.let { artifact ->
                    artifact.extractVersionName()?.let { versionName ->
                        artifact to versionName
                    }
                }
            }.parallelMap { (artifact, versionName) ->
                VersionUi.Remote(
                    androidJob.pkgName,
                    versionName,
                    // Bytes to MiB
                    CommonApi.getContentLength(artifact.url)
                        .getOrNull()
                        ?.let { it / 2.0.pow(20) }
                        ?: .0,
                    versionName == installedVersion.versionName,
                    artifact.url
                )
            }.also {
                allVersions.clear()
                refreshInstalledVersion()
                refreshLocalVersions()
            }.forEach {
                remoteVersions[it.versionName] = it
                if (it.versionName !in localVersions)
                    allVersions[it.versionName] = it
            }
            _isRefreshing.emit(false)
        }
    }

}