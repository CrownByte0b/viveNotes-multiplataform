package com.vivenotes.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.vivenotes.SampleApp
import com.vivenotes.localization.Localization
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Dormant generated entry point. Web is outside the current port scope. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    MainScope().launch {
        Localization.initialize()
        ComposeViewport {
            SampleApp()
        }
    }
}
