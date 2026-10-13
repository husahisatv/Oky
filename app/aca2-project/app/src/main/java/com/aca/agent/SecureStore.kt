package com.aca.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Secrets (API keys, bridge token) live in EncryptedSharedPreferences (Android Keystore-backed AES). */
class SecureStore(ctx: Context) {
    private val secrets: SharedPreferences = EncryptedSharedPreferences.create(
        ctx, "aca_secrets",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val plain: SharedPreferences = ctx.getSharedPreferences("aca_settings", Context.MODE_PRIVATE)

    fun secret(k: String): String = secrets.getString(k, "") ?: ""
    fun putSecret(k: String, v: String) = secrets.edit().putString(k, v.trim()).apply()

    fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            plain.getString("bridgeUrl", d.bridgeUrl) ?: d.bridgeUrl,
            plain.getInt("retryLimit", d.retryLimit), plain.getInt("timeoutSec", d.timeoutSec),
            plain.getString("provider", d.provider) ?: d.provider, plain.getString("model", "") ?: "",
            plain.getBoolean("logging", d.logging), plain.getBoolean("strictVerify", d.strictVerify),
            plain.getBoolean("agentFallback", d.agentFallback)
        )
    }
    fun saveSettings(s: Settings) = plain.edit()
        .putString("bridgeUrl", s.bridgeUrl).putInt("retryLimit", s.retryLimit).putInt("timeoutSec", s.timeoutSec)
        .putString("provider", s.provider).putString("model", s.model)
        .putBoolean("logging", s.logging).putBoolean("strictVerify", s.strictVerify)
        .putBoolean("agentFallback", s.agentFallback).apply()
}
