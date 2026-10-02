package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.SetupCodeEntry

/** The IPTV feature's end of the [SetupCodeEntry] port: everything delegates to the shared controller. */
internal object SetupCodeEntryImpl : SetupCodeEntry {
    override fun acceptLinkedCode(link: String): Boolean {
        SetupPreviewEntry.fromAddPage = false
        return SetupCodeController.shared.acceptLinkedCode(link)
    }

    override fun takeResumeAfterSignIn(): Boolean {
        val resume = SetupCodeController.shared.takeResumeAfterSignIn()
        if (resume) SetupPreviewEntry.fromAddPage = false
        return resume
    }

    override fun prepareCodeEntryPage() {
        XtreamRepository.clearError()
        XtreamAddPage.openAddWithCode()
    }
}
