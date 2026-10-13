package com.aca.agent

import org.json.JSONArray
import org.json.JSONObject

class ProviderException(msg: String) : Exception(msg)

/** Extensible provider contract: add a class + register it in [Providers.all]. */
interface LlmProvider {
    val id: String
    val label: String
    fun listModels(key: String): List<String>           // also serves as the connection test
    fun complete(key: String, model: String, system: String, user: String): String
}

object Providers {
    val all: List<LlmProvider> = listOf(GeminiProvider, OpenRouterProvider)
    fun byId(id: String): LlmProvider = all.firstOrNull { it.id == id } ?: all.first()
}

private fun fail(who: String, r: HttpResult, key: String): Nothing {
    val msg = try {
        val j = JSONObject(r.body)
        when (val e = j.opt("error")) { is JSONObject -> e.optString("message"); is String -> e; else -> r.body }
    } catch (_: Exception) { r.body }
    val hint = when (r.code) {
        400 -> "bad request / model name"
        401, 403 -> "invalid or unauthorized API key"
        404 -> "model not found"
        429 -> "rate limit or quota exceeded"
        in 500..599 -> "provider unavailable"
        else -> ""
    }
    throw ProviderException("$who HTTP ${r.code}${if (hint.isNotEmpty()) " ($hint)" else ""}: ${scrub(msg, key)}")
}

private fun io(who: String, block: () -> String): String = try {
    block()
} catch (e: ProviderException) {
    throw e
} catch (e: java.io.IOException) {
    throw ProviderException("$who network error: ${e.javaClass.simpleName}")
}

object GeminiProvider : LlmProvider {
    override val id = "gemini"
    override val label = "Google Gemini"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    override fun listModels(key: String): List<String> {
        if (key.isBlank()) throw ProviderException("Gemini API key is empty")
        val s = io(label) {
            val r = httpCall("$BASE/models?pageSize=200", "GET", mapOf("x-goog-api-key" to key))
            if (r.code != 200) fail(label, r, key)
            r.body
        }
        val a = JSONObject(s).optJSONArray("models") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { m -> (m.optJSONArray("supportedGenerationMethods")?.toString() ?: "").contains("generateContent") }
            .map { it.getString("name").removePrefix("models/") }
    }

    override fun complete(key: String, model: String, system: String, user: String): String {
        if (key.isBlank()) throw ProviderException("Gemini API key is empty")
        if (model.isBlank()) throw ProviderException("No Gemini model selected")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.1))
        val s = io(label) {
            val r = httpCall("$BASE/models/$model:generateContent", "POST", mapOf("x-goog-api-key" to key), body.toString(), 60000)
            if (r.code != 200) fail(label, r, key)
            r.body
        }
        return JSONObject(s).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            ?: throw ProviderException("Gemini returned no text (blocked or empty response)")
    }
}

object OpenRouterProvider : LlmProvider {
    override val id = "openrouter"
    override val label = "OpenRouter"
    private const val BASE = "https://openrouter.ai/api/v1"

    override fun listModels(key: String): List<String> {
        if (key.isBlank()) throw ProviderException("OpenRouter API key is empty")
        io(label) {   // validates the key (the models list itself is public)
            val r = httpCall("$BASE/auth/key", "GET", mapOf("Authorization" to "Bearer $key"))
            if (r.code != 200) fail(label, r, key)
            r.body
        }
        val s = io(label) {
            val r = httpCall("$BASE/models", "GET", mapOf("Authorization" to "Bearer $key"))
            if (r.code != 200) fail(label, r, key)
            r.body
        }
        val a = JSONObject(s).optJSONArray("data") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it).getString("id") }.sorted()
    }

    override fun complete(key: String, model: String, system: String, user: String): String {
        if (key.isBlank()) throw ProviderException("OpenRouter API key is empty")
        if (model.isBlank()) throw ProviderException("No OpenRouter model selected")
        val body = JSONObject().put("model", model).put("temperature", 0.1).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user)))
        val s = io(label) {
            val r = httpCall("$BASE/chat/completions", "POST", mapOf("Authorization" to "Bearer $key"), body.toString(), 60000)
            if (r.code != 200) fail(label, r, key)
            r.body
        }
        return JSONObject(s).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            ?: throw ProviderException("OpenRouter returned no message")
    }
}
