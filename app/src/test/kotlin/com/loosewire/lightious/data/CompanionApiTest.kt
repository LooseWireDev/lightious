package com.loosewire.lightious.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionApiTest {
    @Test
    fun `device bearer generation and digest match the server contract`() {
        val bearer = "lpt_device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        assertTrue(validDeviceBearer(bearer))
        assertFalse(validDeviceBearer("lpt_device_short"))
        assertFalse(validDeviceBearer("lpt_device_${"A".repeat(42)}B"))
        assertTrue(validPollSecret("lpt_poll_${"A".repeat(43)}"))
        assertFalse(validPollSecret("lpt_poll_${"A".repeat(42)}B"))
        assertEquals(
            "ab4bc1907dd011a72fcf10f36082e3e33c20285615049f5f778fec1edd0b2f73",
            deviceBearerDigest(bearer),
        )
        assertTrue(validDeviceBearer(generateDeviceBearer()))
    }

    @Test
    fun `pairing uses polling credential and sync maps focused items`() = runTest {
        val transport = FakeCompanionTransport()
        val api = CompanionApi("https://invidious.example", transport)

        val pending = api.createPairing("Light Phone III", "a".repeat(64)).getOrThrow()
            .copy(deviceBearer = DEVICE_BEARER)
        val status = api.pairingStatus(pending).getOrThrow()
        val activated = api.activatePairing(pending).getOrThrow()
        val profile = api.sync(DEVICE_BEARER).getOrThrow()

        assertEquals("ABCD-EFGH", pending.userCode)
        assertEquals("https://invidious.example/lightious/pair", pending.verificationUrl)
        assertTrue(status.isClaimed)
        assertEquals(DEVICE_ID, activated.deviceId)
        assertEquals(ExperienceMode.FOCUSED, profile.mode)
        assertEquals(PlaybackPolicy.LISTEN_ONLY, profile.items.single().playbackPolicy)
        assertEquals("https://invidious.example/vi/dQw4w9WgXcQ/mqdefault.jpg", profile.items.single().thumbnailUrl)
        assertTrue(transport.posts.first().body.contains("\"deviceBearerDigest\":\"${"a".repeat(64)}\""))
        assertEquals("Bearer lpt_poll_${"B".repeat(43)}", transport.gets.first().headers["Authorization"])
        assertEquals("Bearer $DEVICE_BEARER", transport.gets.last().headers["Authorization"])
        api.close()
    }

    private data class Request(val url: String, val headers: Map<String, String>, val body: String = "")

    private class FakeCompanionTransport : CompanionHttpTransport {
        val gets = mutableListOf<Request>()
        val posts = mutableListOf<Request>()

        override suspend fun get(url: String, headers: Map<String, String>): InvidiousHttpResponse {
            gets += Request(url, headers)
            return when {
                url.endsWith("/pairings/$PAIRING_ID") -> InvidiousHttpResponse(
                    200,
                    """{"state":"claimed","deviceLabel":"Light Phone III","account":"ga…@example.com","expiresAt":"2026-08-31T20:00:00Z"}""",
                )
                url.endsWith("/sync") -> InvidiousHttpResponse(
                    200,
                    """
                    {
                      "deviceId":"$DEVICE_ID",
                      "account":"ga…@example.com",
                      "revision":4,
                      "mode":"focused",
                      "items":[{
                        "id":"item-1",
                        "videoId":"dQw4w9WgXcQ",
                        "title":"A video",
                        "author":"A channel",
                        "lengthSeconds":213,
                        "thumbnailUrl":"/vi/dQw4w9WgXcQ/mqdefault.jpg",
                        "playbackPolicy":"listen_only"
                      }]
                    }
                    """.trimIndent(),
                )
                else -> error("Unexpected GET: $url")
            }
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            body: String,
        ): InvidiousHttpResponse {
            posts += Request(url, headers, body)
            return when {
                url.endsWith("/pairings") -> InvidiousHttpResponse(
                    201,
                    """
                    {
                      "pairingId":"$PAIRING_ID",
                      "userCode":"ABCD-EFGH",
                      "pollSecret":"lpt_poll_${"B".repeat(43)}",
                      "verificationUrl":"/lightious/pair",
                      "expiresAt":"2026-08-31T20:00:00Z"
                    }
                    """.trimIndent(),
                )
                url.endsWith("/activate") -> InvidiousHttpResponse(
                    200,
                    """{"deviceId":"$DEVICE_ID","account":"ga…@example.com"}""",
                )
                else -> error("Unexpected POST: $url")
            }
        }

        override fun close() = Unit
    }

    private companion object {
        const val PAIRING_ID = "11111111111111111111111111111111"
        const val DEVICE_ID = "22222222222222222222222222222222"
        const val DEVICE_BEARER = "lpt_device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
