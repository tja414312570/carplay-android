package com.carplay.iap2.controller

import com.carplay.iap2.body.Iap2BodyReader
import com.carplay.iap2.wire.Iap2ParameterList
import com.carplay.iap2.wire.Iap2ProtocolException

/** A wireless CarPlay component from the accessory's own identification, not a phone MAC or link session. */
internal data class AccessoryWirelessComponent(
    val id: Int,
    val supported: Boolean,
    val available: Boolean,
)

internal object AccessoryWirelessComponents {
    fun read(body: Iap2BodyReader): List<AccessoryWirelessComponent> = body.all(24).map { parameter ->
        val component = Iap2BodyReader.of(Iap2ParameterList.parse(parameter.payload))
        if (component.all(0).size != 1) throw Iap2ProtocolException("无线 CarPlay 组件编号缺失或重复")
        AccessoryWirelessComponent(
            id = component.u16(0),
            supported = component.optionalVoid(2),
            available = component.optionalVoid(4),
        )
    }

    fun select(body: Iap2BodyReader): AccessoryWirelessComponent {
        val components = read(body)
        check(components.isNotEmpty()) { "车机未提供无线 CarPlay 组件编号，无法构造网络请求" }
        val eligible = components.filter { it.supported && it.available }
        check(eligible.isNotEmpty()) { "车机没有声明可用的无线 CarPlay 组件，请检查车机无线 CarPlay 设置" }
        check(eligible.size == 1) { "车机声明多个可用无线 CarPlay 组件，无法自动确定请求目标" }
        return eligible.single()
    }
}
