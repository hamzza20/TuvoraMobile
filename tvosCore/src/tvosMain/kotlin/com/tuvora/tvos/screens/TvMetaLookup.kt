package com.tuvora.tvos.screens

import com.nuvio.app.core.contracts.MetaSourceAccess
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository

/**
 * A title's details by (type, id) for code that has no page loading it (Continue Watching, the player's next
 * episode). A source's own item (a media server's `ms:` id, an IPTV `xtream:` id) has no add-on meta: its native
 * meta comes from the source itself, exactly as the title page loads it ([MetaDetailsRepository.load]). Add-on and
 * TMDB titles go the usual way.
 */
object TvMetaLookup {
    suspend fun fetch(type: String, id: String): MetaDetails? {
        val own = MetaSourceAccess.current()
        return if (own.handlesId(id)) own.buildNativeMeta(id) else MetaDetailsRepository.fetch(type, id)
    }
}
