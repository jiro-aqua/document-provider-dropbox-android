package jp.gr.aqua.dropbox.provider

import android.os.Bundle
import android.provider.DocumentsContract
import androidx.appcompat.app.AppCompatActivity
import com.dropbox.core.android.Auth
import jp.gr.aqua.dropbox.provider.databinding.ActivityMainBinding


class MainActivity : AppCompatActivity() {

    private val pref by lazy { Preference(this) }

    private lateinit var binding : ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonLogin.setOnClickListener {
            if (pref.hasCredential()) {
                // Log out
                pref.putCredential()

                // BEGIN_INCLUDE(notify_change)
                // Notify the system that the status of our roots has changed.  This will trigger
                // a call to DropboxProvider.queryRoots() and force a refresh of the system
                // picker UI.  It's important to call this or stale results may persist.
                this.contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
                // END_INCLUDE(notify_change)

                showStatus()
            } else {
                // Login
                Auth.startOAuth2PKCE(this, BuildConfig.DROPBOX_APPID, DropboxClientFactory.requestConfig, SCOPES);
            }
        }
        showStatus()
    }

    override fun onResume() {
        super.onResume()

        val credential = Auth.getDbxCredential()
        credential?.let {
            pref.putCredential(credential.toString())
            // BEGIN_INCLUDE(notify_change)
            // Notify the system that the status of our roots has changed.  This will trigger
            // a call to DropboxProvider.queryRoots() and force a refresh of the system
            // picker UI.  It's important to call this or stale results may persist.
            contentResolver.notifyChange(DocumentsContract.buildRootsUri(AUTHORITY), null)
            // END_INCLUDE(notify_change)

            showStatus()
        }
    }

    private fun showStatus() {
        if (pref.hasCredential()) {
            binding.sampleOutput.setText(R.string.logout_message)
            binding.buttonLogin.setText(R.string.log_out)
        } else {
            binding.sampleOutput.setText(R.string.intro_message)
            binding.buttonLogin.setText(R.string.log_in)
        }
    }

    companion object {
        private const val AUTHORITY = "${BuildConfig.APPLICATION_ID}.documents"
        private val SCOPES = arrayListOf("files.metadata.write", "files.metadata.read", "files.content.write", "files.content.read", "account_info.read")
    }

}
