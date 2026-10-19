package com.aitddemo.coop

/** The health page of the server for a WebSocket address (wss://host/... becomes https://host/health), or null when the address is not a WebSocket address. */
fun healthUrl(wsUrl: String): String? {
    val u = wsUrl.trim()
    val rest = when {
        u.startsWith("wss://", ignoreCase = true) -> "https://" + u.substring(6)
        u.startsWith("ws://", ignoreCase = true) -> "http://" + u.substring(5)
        else -> return null
    }
    val authority = rest.substringAfter("://").substringBefore('/').substringBefore('?')
    if (authority.isEmpty()) return null
    return rest.substringBefore("://") + "://" + authority + "/health"
}
