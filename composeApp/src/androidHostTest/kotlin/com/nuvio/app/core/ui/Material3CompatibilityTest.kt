package com.nuvio.app.core.ui

import android.app.Application
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression (2026-09-27): the upstream merge moved Compose to 1.12.0 but left material3 on
 * 1.11.0-alpha07, which was compiled against Compose 1.11. Every OutlinedTextField then crashed at
 * runtime (AbstractMethodError: CustomStyle.applyStyle on OutlinedTextFieldDefaults) — the Sports
 * search box took the app down. Compiling does not catch a binary mismatch; rendering does.
 * Keep material3 on the version upstream pairs with composeMultiplatform.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class Material3CompatibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun outlinedTextFieldRendersAgainstTheBundledComposeFoundation() {
        compose.setContent {
            var value by remember { mutableStateOf("sports") }
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Search") })
        }
        compose.onNodeWithText("sports").assertExists()
    }
}
