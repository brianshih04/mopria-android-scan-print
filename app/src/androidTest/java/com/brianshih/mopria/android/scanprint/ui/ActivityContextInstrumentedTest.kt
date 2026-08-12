package com.brianshih.mopria.android.scanprint.ui

import android.content.ContextWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brianshih.mopria.android.scanprint.MainActivity
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityContextInstrumentedTest {
    @Test
    fun unwrapsActivityFromNestedLocalizedContextWrappers() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val wrapped = ContextWrapper(ContextWrapper(activity))
                assertSame(activity, wrapped.findActivity())
            }
        }
    }
}
