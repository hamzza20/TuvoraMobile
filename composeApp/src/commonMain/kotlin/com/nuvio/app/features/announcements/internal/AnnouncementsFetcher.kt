package com.nuvio.app.features.announcements.internal

import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.announcements.api.Announcement
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `get_app_announcements` — works with the anon key or a signed-in session; server-ordered, max 3. */
internal suspend fun fetchAnnouncementsFromSupabase(): List<Announcement> {
    val result = SupabaseProvider.client.postgrest.rpc(
        "get_app_announcements",
        buildJsonObject { put("p_platform", ANNOUNCEMENTS_PLATFORM) },
    )
    return AnnouncementCodec.decodeWireOrThrow(result.data)
}

/** Android and iOS are both "mobile" to the backend. */
internal const val ANNOUNCEMENTS_PLATFORM = "mobile"
