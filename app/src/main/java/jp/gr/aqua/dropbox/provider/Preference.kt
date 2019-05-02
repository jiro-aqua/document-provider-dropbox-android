package jp.gr.aqua.dropbox.provider

import android.content.Context
import android.preference.PreferenceManager

class Preference(context : Context) {
    private val sp = PreferenceManager.getDefaultSharedPreferences( context )

    fun hasToken(): Boolean {
        val accessToken : String? = sp.getString(PREFKEY, null)
        return accessToken != null
    }
    fun getToken(): String {
        val accessToken : String? = sp.getString(PREFKEY, null)
        return accessToken!!
    }
    fun putToken(token : String = "" ) {
        sp.edit().apply{
            if ( token.isEmpty() ) {
                remove(PREFKEY)
            }else{
                putString(PREFKEY, token)
            }
            apply()
        }
    }

    companion object {
        private val PREFKEY = "dropbox_token"
    }
}