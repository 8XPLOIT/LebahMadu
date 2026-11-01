package com.lebahmadu.app

import android.content.Context

object SessionManager {
    private const val PREFS = "lebah_madu_session"
    private const val KEY_LOGGED_IN = "logged_in"
    private const val KEY_CODE = "login_code"
    private const val KEY_LOGIN_TIME = "login_time"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLoggedIn(context: Context): Boolean = prefs(context).getBoolean(KEY_LOGGED_IN, false)

    fun getCode(context: Context): String? = prefs(context).getString(KEY_CODE, null)

    fun saveSession(context: Context, code: String) {
        prefs(context).edit()
            .putBoolean(KEY_LOGGED_IN, true)
            .putString(KEY_CODE, code)
            .putLong(KEY_LOGIN_TIME, System.currentTimeMillis())
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
