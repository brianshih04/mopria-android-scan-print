package com.brianshih.mopria.android.scanprint

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.brianshih.mopria.android.scanprint.ui.LanguageManager
import com.brianshih.mopria.android.scanprint.ui.MopriaApp
import com.brianshih.mopria.android.scanprint.ui.theme.MopriaTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrap(newBase))
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
