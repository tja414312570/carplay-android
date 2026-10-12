package com.carplay.app

internal data class PendingCaptureStart(val sessionId: String, val peer: String)

/** Owns only a pending request's identifiers; a granted capture token is never stored here. */
internal class CaptureConsentGate {
    var pending: PendingCaptureStart? = null
        private set

    fun open(request: PendingCaptureStart) {
        check(pending == null) { "正在等待系统录屏授权，请先完成或取消" }
        require(request.sessionId.isNotBlank() && request.peer.isNotBlank())
        pending = request
    }

    fun consume(currentSessionId: String, mayStart: Boolean): PendingCaptureStart? {
        val request = pending
        pending = null
        return request?.takeIf { mayStart && it.sessionId == currentSessionId }
    }

    fun clear(sessionId: String) {
        if (pending?.sessionId == sessionId) pending = null
    }
}
