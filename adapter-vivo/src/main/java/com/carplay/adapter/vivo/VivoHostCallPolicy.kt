package com.carplay.adapter.vivo

/** The inspected ComponentVerifier uses this exception before dispatching the AIDL call. */
class VivoHostVerificationException(val operation: String, cause: Throwable) : SecurityException(
    "vivo 宿主拒绝组件版本／签名校验（$operation）；请使用宿主授权的组件，或改用系统授权录屏", cause,
)

object VivoHostCallPolicy {
    fun <T> invoke(operation: String, call: () -> T): T {
        try {
            return call()
        } catch (error: UnsupportedOperationException) {
            if (error.message == "Not yet implemented") throw VivoHostVerificationException(operation, error)
            throw error
        }
    }

    /** Missing optional capabilities may be reported; authorization rejection is always fatal. */
    fun optional(operation: String, diagnostic: (String) -> Unit, call: () -> Unit) {
        try {
            invoke(operation, call)
        } catch (error: SecurityException) {
            throw error
        } catch (error: Exception) {
            diagnostic("$operation 不可用：${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun failureMessage(error: Throwable): String = generateSequence(error) { it.cause }
        .take(8).filterIsInstance<VivoHostVerificationException>().firstOrNull()?.message
        ?: error.message ?: error.javaClass.simpleName
}
