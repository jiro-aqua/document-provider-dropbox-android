package jp.gr.aqua.dropbox.provider

import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.http.OkHttp3Requestor
import com.dropbox.core.v2.DbxClientV2
import java.util.*


/**
 * Singleton instance of [DbxClientV2] and friends
 */
class DropboxClientFactory {
    companion object{
        val requestConfig = DbxRequestConfig.newBuilder(BuildConfig.DROPBOX_HEADER)
                .withUserLocale(Locale.getDefault().toString())
                .withHttpRequestor(OkHttp3Requestor(OkHttp3Requestor.defaultOkHttpClient()))
                .build()
        fun client(accessToken: String) : DbxClientV2 {
            return DbxClientV2(requestConfig, accessToken) //.apply{ Log.d("===DBX===>","${this}")}
        }
    }
}
