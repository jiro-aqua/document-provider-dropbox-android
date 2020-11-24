package jp.gr.aqua.dropbox.provider

import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.http.OkHttp3Requestor
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import java.util.*

/**
 * Singleton instance of [DbxClientV2] and friends
 */
class DropboxClientFactory {
    companion object{
        val requestConfig = DbxRequestConfig.newBuilder(BuildConfig.DROPBOX_HEADER)
                        .withUserLocale(Locale.US.toString())
                        .withHttpRequestor(OkHttp3Requestor(OkHttp3Requestor.defaultOkHttpClient()))
                        .build()
        fun client(serailizedCredental: String) : DbxClientV2 {
            val _credential: DbxCredential = DbxCredential.Reader.readFully(serailizedCredental)
            val credential = DbxCredential(_credential.accessToken, -1L, _credential.refreshToken, _credential.appKey)
            return DbxClientV2(requestConfig, credential) //.apply{ Log.d("===DBX===>","${this}")}
        }
    }
}
