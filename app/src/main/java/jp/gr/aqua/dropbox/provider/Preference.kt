package jp.gr.aqua.dropbox.provider

import android.content.Context

class Preference(context : Context) {
    private val sp = context.getSharedPreferences(PREFFILE,Context.MODE_PRIVATE )

    fun hasCredential(): Boolean {
        val accessToken : String? = sp.getString(PREFKEY, null)
        return accessToken != null
    }
    fun getCredential(): String {
        val accessToken : String? = sp.getString(PREFKEY, null)
        return accessToken!!
    }
    fun putCredential(token : String = "" ) {
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
        private const val PREFKEY = "dropbox_credential"
        private const val PREFFILE = "dropbox_pref"
    }
}