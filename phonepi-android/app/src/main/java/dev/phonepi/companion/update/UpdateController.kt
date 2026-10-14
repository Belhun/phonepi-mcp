package dev.phonepi.companion.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UpdateUiState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val banner: String? = null,
    val message: String? = null,
    val error: String? = null,
    val available: UpdateManifest? = null,
    val lastResult: UpdateRunResult? = null,
)

object UpdateController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(UpdateUiState())
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    fun quietCheck(context: Context) {
        scope.launch {
            mutex.withLock {
                val engine = UpdateEngine(context)
                _state.value = _state.value.copy(checking = true, error = null)
                try {
                    val manifest = engine.fetchManifest()
                    val newer = manifest.isNewerThan(engine.installedVersionCode())
                    _state.value = _state.value.copy(
                        checking = false,
                        available = manifest,
                        banner = if (newer) {
                            "Update ${manifest.versionName} (${manifest.versionCode}) is ready"
                        } else {
                            null
                        },
                    )
                } catch (_: Exception) {
                    _state.value = _state.value.copy(checking = false)
                }
            }
        }
    }

    fun checkAndInstall(context: Context, force: Boolean) {
        scope.launch { install(context, force) }
    }

    suspend fun install(context: Context, force: Boolean): UpdateRunResult {
        return mutex.withLock {
            val engine = UpdateEngine(context)
            _state.value = _state.value.copy(
                checking = true,
                downloading = false,
                error = null,
                message = if (force) "Downloading APK…" else "Checking for update…",
                progress = 0f,
            )
            val result = engine.run(force) { received, total ->
                val fraction = if (total > 0) received.toFloat() / total.toFloat() else 0f
                _state.value = _state.value.copy(
                    checking = false,
                    downloading = true,
                    progress = fraction.coerceIn(0f, 1f),
                    message = if (total > 0) {
                        "Downloading ${received / 1024} / ${total / 1024} KB"
                    } else {
                        "Downloading ${received / 1024} KB"
                    },
                )
            }
            val message = when {
                result.error != null -> result.error
                result.skipped -> "Already on ${result.serverVersionName ?: "this version"}"
                result.promptShown -> "System installer opened — tap Install. PhonePi never installs silently."
                else -> result.messageOrDefault()
            }
            _state.value = _state.value.copy(
                checking = false,
                downloading = false,
                progress = if (result.downloaded) 1f else _state.value.progress,
                error = result.error,
                message = if (result.error == null) message else null,
                banner = if (result.serverVersionCode != null &&
                    result.serverVersionCode > result.currentVersionCode
                ) {
                    "Update ${result.serverVersionName} (${result.serverVersionCode}) is ready"
                } else {
                    _state.value.banner
                },
                lastResult = result,
            )
            result
        }
    }

    private fun UpdateRunResult.messageOrDefault(): String =
        if (promptShown) "Installer prompt shown" else "Update did not show an installer prompt"
}
