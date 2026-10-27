package hu.reelee81.reepdf.core

object PdfOperationScreen {
    sealed interface FreshStart {
        data class ClearCache(val acknowledge: Long?) : FreshStart
        data class Discard(val cancel: Long) : FreshStart
    }

    sealed interface Step {
        data object Ignore : Step
        data class Adopt(val id: Long) : Step
        data class Attach(val id: Long, val message: String, val updateUi: Boolean) : Step

        sealed interface Ended : Step {
            val id: Long
        }

        data class Finish(override val id: Long, val result: PdfOperationResult?) : Ended
        data class Fail(override val id: Long, val error: Throwable) : Ended
        data class Restore(override val id: Long) : Ended
        data class Discarded(override val id: Long) : Ended
    }

    fun onFreshStart(state: PdfOperationState): FreshStart {
        val request = state.request
        return if (state.isBusy && request != null) FreshStart.Discard(request.id)
        else FreshStart.ClearCache(request?.id)
    }

    fun isPending(state: PdfOperationState): Boolean = state !is PdfOperationState.Idle

    fun onFinishing(state: PdfOperationState): Long? = state.request?.id?.takeIf { state.isBusy }

    fun step(
        state: PdfOperationState,
        shownId: Long?,
        overlayOwned: Boolean,
        discarding: Boolean,
        handedOverId: Long? = null
    ): Step {
        val request = state.request ?: return Step.Ignore
        if (state.isBusy) {
            return when {
                shownId == request.id -> Step.Ignore
                overlayOwned -> Step.Adopt(request.id)
                else -> Step.Attach(request.id, request.message, discarding || state is PdfOperationState.Cancelling)
            }
        }
        if (handedOverId == request.id) return Step.Ignore
        if (discarding) return Step.Discarded(request.id)
        return when (state) {
            is PdfOperationState.Done -> Step.Finish(request.id, state.result)
            is PdfOperationState.Failed -> Step.Fail(request.id, state.error)
            is PdfOperationState.Cancelled -> Step.Restore(request.id)
            else -> Step.Ignore
        }
    }
}
