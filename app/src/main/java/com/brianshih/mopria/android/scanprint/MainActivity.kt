package com.brianshih.mopria.android.scanprint

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.brianshih.mopria.android.scanprint.ui.LanguageManager
import com.brianshih.mopria.android.scanprint.ui.MopriaApp
import com.brianshih.mopria.android.scanprint.ui.theme.MopriaTheme
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // Keep the Activity attached directly to Android's ContextImpl. Wrapping the base in a
        // configuration context causes cached system services such as PrintManager to retain the
        // wrapper instead of this Activity, and PrintManager then rejects print() calls.
        super.attachBaseContext(newBase)
        val localeOverride = Configuration().apply {
            setLocale(Locale.forLanguageTag(LanguageManager.resolve(newBase).tag))
        }
        applyOverrideConfiguration(localeOverride)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MopriaTheme {
                MopriaApp()
            }
        }
    }
}
