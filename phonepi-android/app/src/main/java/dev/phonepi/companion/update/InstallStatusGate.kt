package dev.phonepi.companion.update

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred

object InstallStatusGate {
    @Volatile
    var pending: CompletableDeferred<Intent?>? = null
}
