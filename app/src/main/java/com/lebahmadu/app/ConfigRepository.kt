package com.lebahmadu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Expected config.json:
 * {
 *   "login_enabled": true,
 *   "codes": ["HONEY-1234", "HONEY-5678"]
 * }
 */
data class RemoteConfig(val loginEnabled: Boolean, val codes: Set<String>)

enum class ConfigError { NO_INTERNET, TIMEOUT, NETWORK, SERVER, BAD_CONFIG }

sealed interface ConfigResult {
    data class Success(val config: RemoteConfig) : ConfigResult
    data class Failure(val error: ConfigError, val httpCode: Int = 0) : ConfigResult
}

object ConfigRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): ConfigResult = withContext(Dispatchers.IO) {
        try {
            // Cache-buster: raw.githubusercontent.com caches per full URL for a few minutes.
            val url = AppConfig.CONFIG_URL.toHttpUrl().newBuilder()
                .addQueryParameter("t", System.currentTimeMillis().toString())
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ConfigResult.Failure(ConfigError.SERVER, response.code)
                } else {
                    val body = response.body?.string().orEmpty()
                    ConfigResult.Success(parse(body))
                }
            }
        } catch (e: UnknownHostException) {
            ConfigResult.Failure(ConfigError.NO_INTERNET)
        } catch (e: SocketTimeoutException) {
            ConfigResult.Failure(ConfigError.TIMEOUT)
        } catch (e: IOException) {
            ConfigResult.Failure(ConfigError.NETWORK)
        } catch (e: JSONException) {
            ConfigResult.Failure(ConfigError.BAD_CONFIG)
        }
    }

    private fun parse(raw: String): RemoteConfig {
        val json = JSONObject(raw)
        val enabled = json.getBoolean("login_enabled")
        val array = json.optJSONArray("codes") ?: JSONArray()
        val codes = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val code = array.optString(i).trim()
            if (code.isNotEmpty()) codes.add(code)
        }
        return RemoteConfig(enabled, codes)
    }
}
