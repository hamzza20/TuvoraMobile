package com.nuvio.app.features.iptv

import com.nuvio.app.core.contracts.SetupCodeEntry

/** The IPTV feature's end of the [SetupCodeEntry] port: everything delegates to the shared controller. */
internal object SetupCodeEntryImpl : SetupCodeEntry {
    override fun acceptLinkedCode(link: String): Boolean = SetupCodeController.shared.acceptLinkedCode(link)

    override fun takeResumeAfterSignIn(): Boolean = SetupCodeController.shared.takeResumeAfterSignIn()

    override fun prepareCodeEntryPage() {
        XtreamRepository.clearError()
        XtreamAddPage.openAddWithCode()
    }
}
