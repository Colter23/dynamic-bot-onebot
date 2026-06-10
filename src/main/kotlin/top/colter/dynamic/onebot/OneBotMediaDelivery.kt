package top.colter.dynamic.onebot

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URI

internal fun oneBotWebSocketHost(url: String): String? {
    return runCatching { URI(url).host }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

internal fun oneBotSameHostLikely(host: String?): Boolean {
    val value = host?.trim()?.takeIf { it.isNotBlank() } ?: return false
    if (value.equals("localhost", ignoreCase = true)) return true
    return runCatching {
        InetAddress.getAllByName(value).any { address -> address.isLoopbackAddress || address.isLocalInterfaceAddress() }
    }.getOrDefault(false)
}

internal fun oneBotSameHostLikely(address: InetSocketAddress?): Boolean {
    val inetAddress = address?.address ?: return false
    return inetAddress.isLoopbackAddress || inetAddress.isLocalInterfaceAddress()
}

internal fun oneBotSignedUrlBaseCandidates(
    webAdminHost: String,
    webAdminPort: Int,
    sameHostLikely: Boolean,
): List<String> {
    if (webAdminPort !in 1..65_535) return emptyList()
    return buildList {
        if (sameHostLikely) {
            add("http://127.0.0.1:$webAdminPort")
            add("http://localhost:$webAdminPort")
        }
        val host = webAdminHost.trim()
        if (host.isNotBlank() && !host.isWildcardBindAddress()) {
            add("http://$host:$webAdminPort")
        }
        if (host.isWildcardBindAddress()) {
            localInterfaceAddresses()
                .filterNot { it.isLoopbackAddress }
                .map { "http://${it.hostAddress}:$webAdminPort" }
                .take(6)
                .forEach(::add)
        }
    }.distinct()
}

private fun InetAddress.isLocalInterfaceAddress(): Boolean {
    return localInterfaceAddresses().any { it == this }
}

private fun localInterfaceAddresses(): List<InetAddress> {
    return runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterNot { it.isAnyLocalAddress || it.hostAddress.contains(":") }
            .toList()
    }.getOrDefault(emptyList())
}

private fun String.isWildcardBindAddress(): Boolean {
    val value = trim().lowercase()
    return value == "0.0.0.0" || value == "::" || value == "[::]"
}
