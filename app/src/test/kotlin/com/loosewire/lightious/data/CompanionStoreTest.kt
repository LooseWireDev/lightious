package com.loosewire.lightious.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CompanionStoreTest {
    @Test
    fun `device credential is encrypted and cached profile is instance scoped`() = runTest {
        val dataStore = createDataStore(File.createTempFile("lightious-companion", ".preferences_pb"))
        val cipher = FakeCipher()
        val store = CompanionStore(dataStore, cipher)
        val session = CompanionSession(
            instanceUrl = "Example.COM:443/",
            deviceId = "device-1",
            account = "ga…@example.com",
            deviceBearer = DEVICE_BEARER,
        )
        val profile = CompanionProfile(
            deviceId = "device-1",
            account = "ga…@example.com",
            revision = 3,
            mode = ExperienceMode.FOCUSED,
            items = listOf(
                CuratedVideo(
                    id = "item-1",
                    videoId = "dQw4w9WgXcQ",
                    title = "A video",
                    author = "A channel",
                    lengthSeconds = 213,
                    playbackPolicy = PlaybackPolicy.LISTEN_ONLY,
                ),
            ),
        )

        store.saveSession(session)
        store.saveProfile("https://example.com", profile)

        assertEquals(CompanionState(session.copy(instanceUrl = "https://example.com"), profile), store.load("example.com"))
        assertEquals(CompanionState(), store.load("https://other.example"))
        assertFalse(
            dataStore.data.first().asMap().values
                .map(Any::toString)
                .any { value -> value.contains(DEVICE_BEARER) },
        )

        store.clear()
        assertNull(store.load("https://example.com").session)
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

    private companion object {
        const val DEVICE_BEARER = "lpt_device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
