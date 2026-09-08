package org.box44.kailink

import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginInstrumentationTest {
    @Test
    fun packageMetadataAndMatrixOrgPrefill() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("org.box44.kailink", context.packageName)
        assertEquals("0.2.0-phase1", packageInfo.versionName)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("https://matrix.org", activity.findViewById<EditText>(R.id.input_homeserver).text.toString())
            }
        }
    }
}
