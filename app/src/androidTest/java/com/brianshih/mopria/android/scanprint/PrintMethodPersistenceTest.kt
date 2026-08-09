package com.brianshih.mopria.android.scanprint

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.brianshih.mopria.android.scanprint.domain.PrintMethod
import com.brianshih.mopria.android.scanprint.ui.MopriaViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Direct IPP vs System choice is a user setting persisted in `mopria_settings`. Verify it survives
 * a ViewModel recreation (the in-process stand-in for an activity/process restart) so the selected
 * print method is honored on the next print.
 */
@RunWith(AndroidJUnit4::class)
class PrintMethodPersistenceTest {

    @Test
    fun printMethodPersistsAcrossViewModelInstances() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()

        MopriaViewModel(app).setPrintMethod(PrintMethod.Ipp)

        val reloaded = MopriaViewModel(app)
        assertEquals(PrintMethod.Ipp, reloaded.uiState.value.printMethod)
    }

    @Test
    fun printMethodDefaultsToSystem() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = MopriaViewModel(app)

        assertEquals(PrintMethod.System, viewModel.uiState.value.printMethod)
    }
}
