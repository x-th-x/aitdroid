import com.aitddemo.coop.healthUrl

// Tests of the health address that is used to wake a sleeping server (CoopAddress.kt), on a plain JVM
var failures = 0

fun check(ok: Boolean, text: String) {
    println((if (ok) "ok   " else "FAIL ") + text)
    if (!ok) failures++
}

fun main() {
    check(healthUrl("wss://aitdroid-coop.onrender.com") == "https://aitdroid-coop.onrender.com/health", "wss without a path")
    check(healthUrl("  wss://aitdroid-coop.onrender.com/  ") == "https://aitdroid-coop.onrender.com/health", "spaces and a slash")
    check(healthUrl("ws://192.168.1.20:8080") == "http://192.168.1.20:8080/health", "ws with a port (local test)")
    check(healthUrl("WSS://Host.example/play?x=1") == "https://Host.example/health", "capital letters, a path and a query are dropped")
    check(healthUrl("https://aitdroid-coop.onrender.com") == null, "an https address is not a WebSocket address")
    check(healthUrl("wss://") == null, "no host")
    check(healthUrl("") == null, "empty")
    println(if (failures == 0) "ALL OK" else "$failures FAILED")
}
