package io.carreader.app.ui.confirm

import io.carreader.core.guard.CheckResult
import io.carreader.core.guard.ConfirmationPort
import io.carreader.core.guard.MutationRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ConfirmationPort] backed by a Compose dialog: [confirm] publishes a [PendingConfirmation] and suspends until
 * the UI calls [answer]. One question at a time; dismissing the dialog counts as "no".
 */
class DialogConfirmationPort : ConfirmationPort {
    data class PendingConfirmation(
        val request: MutationRequest,
        val checks: List<Pair<String, CheckResult>>,
    )

    private val mutex = Mutex()
    private val _pending = MutableStateFlow<PendingConfirmation?>(null)
    val pending: StateFlow<PendingConfirmation?> = _pending.asStateFlow()

    @Volatile private var answerSignal: CompletableDeferred<Boolean>? = null

    override suspend fun confirm(request: MutationRequest, checks: List<Pair<String, CheckResult>>): Boolean =
        mutex.withLock {
            val signal = CompletableDeferred<Boolean>()
            answerSignal = signal
            _pending.value = PendingConfirmation(request, checks)
            try {
                signal.await()
            } finally {
                answerSignal = null
                _pending.value = null
            }
        }

    fun answer(accepted: Boolean) {
        answerSignal?.complete(accepted)
    }
}
