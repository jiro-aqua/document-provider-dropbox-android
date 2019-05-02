package jp.gr.aqua.dropbox.provider

import android.os.Bundle
import android.provider.DocumentsContract
import android.support.v7.app.AppCompatActivity
import com.dropbox.core.android.Auth
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    private val pref by lazy { Preference(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        button_login.setOnClickListener {
            if (pref.hasToken()) {
                // Log out
                pref.putToken()

                // BEGIN_INCLUDE(notify_change)
                // Notify the system that the status of our roots has changed.  This will trigger
                // a call to DropboxProvider.queryRoots() and force a refresh of the system
                // picker UI.  It's important to call this or stale results may persist.
                this.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null, false)
                // END_INCLUDE(notify_change)

                showStatus()
            } else {
                // Login
                Auth.startOAuth2Authentication(this, BuildConfig.DROPBOX_APPID)
            }
        }
        showStatus()
    }

    override fun onResume() {
        super.onResume()
        val token = Auth.getOAuth2Token()
        token?.let {
            pref.putToken(token)

            // BEGIN_INCLUDE(notify_change)
            // Notify the system that the status of our roots has changed.  This will trigger
            // a call to DropboxProvider.queryRoots() and force a refresh of the system
            // picker UI.  It's important to call this or stale results may persist.
            contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null, false)
            // END_INCLUDE(notify_change)

            showStatus()
        }
    }

    private fun showStatus() {
        if (pref.hasToken()) {
            sample_output.setText(R.string.logout_message)
            button_login.setText(R.string.log_out)
        } else {
            sample_output.setText(R.string.intro_message)
            button_login.setText(R.string.log_in)
        }
    }

    companion object {
        private const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.documents"
    }

}
