package com.aca.agent

import java.net.HttpURLConnection
import java.net.URL

class HttpResult(val code: Int, val body: String)

/** Minimal blocking HTTP helper (call from Dispatchers.IO). Never logs headers/URLs with secrets. */
fun httpCall(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(),
             body: String? = null, timeoutMs: Int = 15000): HttpResult {
    val c = URL(url).openConnection() as HttpURLConnection
    try {
        c.requestMethod = method; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code in 200..399) c.inputStream else c.errorStream
        return HttpResult(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
    } finally { c.disconnect() }
}

/** Remove anything that looks like a credential before showing/logging an error. */
fun scrub(s: String, vararg secrets: String): String {
    var r = s
    for (x in secrets) if (x.length >= 6) r = r.replace(x, "***")
    return r.replace(Regex("(AIza|sk-)[A-Za-z0-9_-]{10,}"), "***").take(300)
}
