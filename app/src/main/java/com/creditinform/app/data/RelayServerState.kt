package com.creditinform.app.data

internal data class RelayServerStateDecision(
    val changed: Boolean,
    val resetCursor: Boolean,
    val cursor: Long,
    val cursorInitialized: Boolean,
    val serverInstanceId: String?
)

internal fun decideRelayServerState(
    storedInstanceId: String?,
    incomingInstanceId: String?,
    cursor: Long,
    serverCursor: Long,
    cursorInitialized: Boolean,
    initializeIfNeeded: Boolean,
    checkCursorRegression: Boolean
): RelayServerStateDecision {
    val firstInstanceBinding = storedInstanceId == null && incomingInstanceId != null &&
        cursorInitialized
    val instanceChanged = storedInstanceId != null && storedInstanceId != incomingInstanceId
    val cursorRegressed = cursorInitialized && checkCursorRegression &&
        serverCursor.coerceAtLeast(0) < cursor
    val resetCursor = firstInstanceBinding || instanceChanged || cursorRegressed
    val initializeCursor = !cursorInitialized && initializeIfNeeded && !resetCursor
    val nextCursor = when {
        resetCursor -> 0L
        initializeCursor -> serverCursor.coerceAtLeast(0)
        else -> cursor
    }
    return RelayServerStateDecision(
        changed = resetCursor || initializeCursor || storedInstanceId != incomingInstanceId,
        resetCursor = resetCursor,
        cursor = nextCursor,
        cursorInitialized = cursorInitialized || resetCursor || initializeCursor,
        serverInstanceId = incomingInstanceId
    )
}
