package hu.reelee81.reepdf.core

enum class PdfOperationKind {
    CROP,
    ROTATION,
    POSITION,
    LAYOUT,
    DELETE_GROUP,
    DELETE_SELECTED,
    EMPTY_PAGE,
    REORDER,
    IMPORT,
    VIEWER_EDIT,
    SIGN,
    PRINT,
    SEND,
    OPEN,
    SAVE_EXPORT,
    SAVE_WRITE
}

data class PdfOperationRequest(
    val id: Long,
    val kind: PdfOperationKind,
    val message: String
)

interface PdfOperationResult {
    val pageSizes: PageSizes?
        get() = null
}

sealed interface PdfOperationState {
    val request: PdfOperationRequest?

    data object Idle : PdfOperationState {
        override val request: PdfOperationRequest? = null
    }

    data class Running(
        override val request: PdfOperationRequest,
        val percent: Int? = null
    ) : PdfOperationState

    data class Cancelling(override val request: PdfOperationRequest) : PdfOperationState

    data class Done(
        override val request: PdfOperationRequest,
        val result: PdfOperationResult? = null
    ) : PdfOperationState

    data class Cancelled(override val request: PdfOperationRequest) : PdfOperationState

    data class Failed(
        override val request: PdfOperationRequest,
        val error: Throwable
    ) : PdfOperationState

    val isBusy: Boolean
        get() = this is Running || this is Cancelling

    fun start(next: PdfOperationRequest): PdfOperationState? =
        if (isBusy) null else Running(next)

    fun progress(id: Long, percent: Int?): PdfOperationState =
        if (this is Running && request.id == id) copy(percent = percent?.coerceIn(0, 100)) else this

    fun cancel(id: Long): PdfOperationState =
        if (this is Running && request.id == id) Cancelling(request) else this

    fun finish(id: Long, result: PdfOperationResult? = null): PdfOperationState =
        if (this is Running && request.id == id) Done(request, result) else this

    fun fail(id: Long, error: Throwable): PdfOperationState =
        if (this is Running && request.id == id) Failed(request, error) else this

    fun cancelled(id: Long): PdfOperationState {
        val current = request
        return if (isBusy && current != null && current.id == id) Cancelled(current) else this
    }

    fun cancelFailed(id: Long, error: Throwable): PdfOperationState {
        val current = request
        return if (isBusy && current != null && current.id == id) Failed(current, error) else this
    }

    fun acknowledge(id: Long): PdfOperationState {
        val current = request
        return if (!isBusy && current != null && current.id == id) Idle else this
    }
}
