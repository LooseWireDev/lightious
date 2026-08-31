package com.loosewire.lightious.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionRepositoryTest {
    @Test
    fun `focused mode allows only curated videos and preserves listen-only policy`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-policy", ".preferences_pb")),
            FakeCipher(),
        )
        store.saveSession(
            CompanionSession(
                "https://invidious.example",
                DEVICE_ID,
                "gav",
                DEVICE_BEARER,
            ),
        )
        val transport = FocusedSyncTransport()
        val repository = CompanionRepository(store) { url -> CompanionApi(url, transport) }

        val allowed = repository.authorizePlayback("https://invidious.example", "dQw4w9WgXcQ")
        val denied = repository.authorizePlayback("https://invidious.example", "aqz-KE-bpKQ")

        assertTrue(allowed.allowed)
        assertEquals(PlaybackPolicy.LISTEN_ONLY, allowed.policy)
        assertFalse(denied.allowed)
        assertEquals("This video is not in your Focused library.", denied.message)
        assertEquals(2, transport.syncCalls)
    }

    @Test
    fun `paired playback fails closed when current policy cannot be synced`() = runTest {
        val store = CompanionStore(
            createDataStore(File.createTempFile("lightious-revoked", ".preferences_pb")),
            FakeCipher(),
        )
        store.saveSession(
            CompanionSession(
                "https://invidious.example",
                DEVICE_ID,
                "gav",
                DEVICE_BEARER,
            ),
        )
        store.saveProfile(
            "https://invidious.example",
            CompanionProfile(
                deviceId = DEVICE_ID,
                account = "gav",
                revision = 1,
                mode = ExperienceMode.FOCUSED,
                items = emptyList(),
            ),
        )
        val repository = CompanionRepository(store) { url -> CompanionApi(url, UnauthorizedSyncTransport) }

        val access = repository.authorizePlayback("https://invidious.example", "dQw4w9WgXcQ")

        assertFalse(access.allowed)
        assertTrue(access.message?.contains("HTTP 401") == true)
    }

    private fun TestScope.createDataStore(file: File): DataStore<Preferences> {
        file.delete()
        return PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
    }

    private class FakeCipher : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray =
            "encrypted:${plaintext.reversed()}".toByteArray(StandardCharsets.UTF_8)

        override fun decrypt(blob: ByteArray): String =
            String(blob, StandardCharsets.UTF_8).removePrefix("encrypted:").reversed()
    }

    private class FocusedSyncTransport : CompanionHttpTransport {
        var syncCalls = 0

        override suspend fun get(url: String, headers: Map<String, String>): InvidiousHttpResponse {
            check(url.endsWith("/sync"))
            syncCalls += 1
            return InvidiousHttpResponse(
                200,
                """
                {
                  "deviceId":"$DEVICE_ID",
                  "account":"gav",
                  "revision":7,
                  "mode":"focused",
                  "items":[{
                    "id":"item-1",
                    "videoId":"dQw4w9WgXcQ",
                    "title":"A video",
                    "author":"A channel",
                    "lengthSeconds":213,
                    "playbackPolicy":"listen_only"
                  }]
                }
                """.trimIndent(),
            )
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): InvidiousHttpResponse = error("Unexpected POST: $url")

        override fun close() = Unit
    }

    private data object UnauthorizedSyncTransport : CompanionHttpTransport {
        override suspend fun get(url: String, headers: Map<String, String>) =
            InvidiousHttpResponse(401, "Invalid device credential.")

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): InvidiousHttpResponse = error("Unexpected POST: $url")

        override fun close() = Unit
    }

    private companion object {
        const val DEVICE_ID = "22222222222222222222222222222222"
        const val DEVICE_BEARER = "lpt_device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
