package com.carplay.sender

import java.net.InetAddress
import java.net.Inet4Address
import java.net.Inet6Address

data class PeerAddress(val remote: InetAddress, val local: InetAddress)

/** Addresses from authenticated iAP2 are numeric; a car's scope ID is never a phone interface. */
object PeerAddresses {
    fun parse(value: String): InetAddress {
        require(value.length in 1..128 && value == value.trim()) { "车机 IP 地址格式无效" }
        val parts = value.split('%')
        require(parts.size <= 2 && (parts.size == 1 || parts[1].matches(Regex("[A-Za-z0-9_.-]{1,64}")))) {
            "车机 IPv6 区域标识无效"
        }
        val literal = parts[0]
        val address = if (':' in literal) {
            require(literal.matches(Regex("[0-9A-Fa-f:.]+"))) { "车机必须提供数字 IP 地址" }
            InetAddress.getByName(literal)
        } else {
            require(parts.size == 1 && literal.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))) { "车机必须提供数字 IP 地址" }
            val bytes = literal.split('.').map { it.toInt().also { number -> require(number in 0..255) } }.map { it.toByte() }.toByteArray()
            InetAddress.getByAddress(bytes)
        }
        require(!address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isMulticastAddress) { "车机 IP 地址不可用于单播连接" }
        return address
    }

    fun resolve(values: List<String>, locals: List<InetAddress>, interfaceIndex: Int): List<PeerAddress> {
        require(values.size <= 16) { "车机地址数量超过限制" }
        val validLocals = locals.filter { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isMulticastAddress }
        return values.map(::parse).mapNotNull { remote ->
            val local = validLocals.firstOrNull {
                when (remote) {
                    is Inet4Address -> it is Inet4Address
                    is Inet6Address -> it is Inet6Address && it.isLinkLocalAddress == remote.isLinkLocalAddress
                    else -> false
                }
            } ?: return@mapNotNull null
            if (remote is Inet6Address && remote.isLinkLocalAddress) {
                val scope = interfaceIndex.takeIf { it > 0 } ?: (local as Inet6Address).scopeId
                if (scope <= 0) return@mapNotNull null
                PeerAddress(Inet6Address.getByAddress(null, remote.address, scope), Inet6Address.getByAddress(null, local.address, scope))
            } else PeerAddress(remote, local)
        }.distinctBy { it.remote.address.toList() }
    }

    fun matches(values: List<String>, candidate: InetAddress): Boolean =
        values.any { parse(it).address.contentEquals(candidate.address) }
}
