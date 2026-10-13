package com.aca.agent

import org.json.JSONArray
import org.json.JSONObject

/** Talks to aca_bridge.py in Termux. All methods are blocking; call from Dispatchers.IO. */
class BridgeClient(private val baseUrl: String, private val token: String) {
    class BridgeError(val kind: String, msg: String) : Exception(msg)

    private fun req(method: String, path: String, body: JSONObject? = null): JSONObject {
        if (token.isBlank()) throw BridgeError("no_token", "Bridge token is not set (Settings)")
        val r = try {
            httpCall(baseUrl.trimEnd('/') + path, method, mapOf("Authorization" to "Bearer $token"), body?.toString(), 20000)
        } catch (e: java.io.IOException) {
            throw BridgeError("unreachable", "Bridge unreachable: is Termux running aca_bridge.py? (${e.javaClass.simpleName})")
        }
        val j = try { JSONObject(r.body.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
        if (r.code == 401) throw BridgeError("unauthorized", "Bridge rejected the token")
        if (r.code !in 200..299) throw BridgeError(j.optString("error", "http_${r.code}"), j.optString("detail", j.optString("error", "HTTP ${r.code}")))
        return j
    }

    fun health(deep: Boolean): Health = try {
        val j = req("GET", "/health" + if (deep) "?deep=1" else "")
        Health(true, j.optBoolean("engine_present"), j.optBoolean("rish_present"), j.optBoolean("control_sh_present"),
            j.optString("busy").takeIf { it.isNotBlank() && it != "null" }, j.optString("foreground").ifBlank { null },
            j.optString("gateway").ifBlank { null }, agent = j.optBoolean("agent_mode"))
    } catch (e: BridgeError) { Health(false, false, false, false, null, null, null, e.message) }

    fun skills(): List<Skill> {
        val a = req("GET", "/skills").getJSONArray("skills")
        return (0 until a.length()).map { val o = a.getJSONObject(it)
            Skill(o.getString("name"), o.optString("app"), o.optString("target"), o.optString("control"), o.optInt("steps"),
                o.optBoolean("runnable"), o.optBoolean("needs_confirmation"), o.optString("evidence"), o.optString("note")) }
    }

    /** Returns the task id. [confirmed] must only be true after the user approved in the UI. */
    fun submit(skill: String?, intent: String?, timeout: Int, confirmed: Boolean, agent: String? = null): String {
        val b = JSONObject().put("timeout", timeout)
        when { skill != null -> b.put("skill", skill); agent != null -> b.put("agent", agent); else -> b.put("intent", intent) }
        if (confirmed) b.put("confirmed", true)
        return req("POST", "/tasks", b).getString("id")
    }

    fun task(id: String): TaskView = parseTask(req("GET", "/tasks/$id"))
    fun cancel(id: String) { try { req("POST", "/tasks/$id/cancel") } catch (_: BridgeError) {} }
    fun history(): List<TaskView> {
        val a = req("GET", "/tasks").getJSONArray("tasks")
        return (0 until a.length()).map { parseTask(a.getJSONObject(it)) }
    }

    private fun parseTask(o: JSONObject): TaskView {
        val out = o.optJSONArray("output") ?: o.optJSONArray("steps_seen") ?: JSONArray()
        return TaskView(o.getString("id"), o.optString("kind"), o.optString("skill").takeIf { it != "null" && it.isNotEmpty() },
            o.optString("intent").takeIf { it != "null" && it.isNotEmpty() }, o.getString("state"), o.optString("evidence"),
            o.optString("reason"), if (o.isNull("exit_code")) null else o.optInt("exit_code"),
            o.optDouble("started"), if (o.isNull("ended")) null else o.optDouble("ended"),
            (0 until out.length()).map { out.getString(it) })
    }
}
