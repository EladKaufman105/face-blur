package com.elad.faceblur

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.IntentCompat
import com.elad.faceblur.i18n.Lang
import com.elad.faceblur.ui.FaceBlurTheme
import com.elad.faceblur.ui.MainScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(applicationContext)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        // CI's check of the shipped build: a video it copied into the app's own folder.
        intent?.getStringExtra("selftest")?.let { name ->
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch { SelfTest.run(applicationContext, name) }
        }
        setContent {
            val prefs = vm.prefs
            FaceBlurTheme(prefs.themeMode, prefs.palette) {
                // The UI direction follows the app's language, not the phone's.
                val dir = if (prefs.lang == Lang.HE) LayoutDirection.Rtl else LayoutDirection.Ltr
                CompositionLocalProvider(LocalLayoutDirection provides dir) {
                    MainScreen(vm)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app: the open project is saved right away.
        vm.saveProject()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return
        vm.load(uri)
    }
}
