package com.carplay.adapter.vivo

import android.os.IBinder
import android.os.Parcel

object VivoHostBinder {
    const val MANAGER = "com.baidu.carlife.mixing.aidl.ICarLifeCastManager"

    /** Read-only query: binding alone does not prove that the host accepts this caller. */
    fun requireManagerDescriptor(remote: IBinder): String = VivoHostCallPolicy.invoke("查询宿主投屏接口") {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            check(remote.transact(IBinder.INTERFACE_TRANSACTION, data, reply, 0)) { "宿主未提供投屏接口描述符" }
            check(reply.dataAvail() >= 4) { "宿主投屏接口回复为空" }
            // Binder's normal INTERFACE_TRANSACTION reply is a string, without an exception
            // header. A failed host onTransact writes an exception instead. Inspect its
            // negative header before reading a string, and let Parcel propagate the error.
            val position = reply.dataPosition()
            val header = reply.readInt()
            reply.setDataPosition(position)
            if (header < 0) reply.readException()
            val descriptor = reply.readString()
            check(descriptor == MANAGER) { "宿主投屏接口不匹配：${descriptor.orEmpty()}" }
            descriptor
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
