package com.loosewire.lightious.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ExperienceModeTest {
    @Test
    fun `defaults to focused until the companion provides a profile`() {
        val profile: CompanionProfile? = null

        assertEquals(ExperienceMode.FOCUSED, profile.effectiveExperienceMode())
    }

    @Test
    fun `keeps an explicit explore profile as the fallback experience`() {
        val profile = CompanionProfile(
            deviceId = "device",
            account = "account",
            revision = 1,
            mode = ExperienceMode.EXPLORE,
            items = emptyList(),
        )

        assertEquals(ExperienceMode.EXPLORE, profile.effectiveExperienceMode())
    }

    @Test
    fun `discarding an unverified profile preserves its paired session and fails closed`() {
        val session = CompanionSession(
            instanceUrl = "https://invidious.example",
            deviceId = "device",
            account = "account",
            deviceBearer = "credential",
        )
        val staleExplore = CompanionState(
            session = session,
            profile = CompanionProfile(
                deviceId = "device",
                account = "account",
                revision = 1,
                mode = ExperienceMode.EXPLORE,
                items = emptyList(),
            ),
        )

        val failClosed = staleExplore.withoutUnverifiedProfile()

        assertEquals(session, failClosed.session)
        assertEquals(null, failClosed.profile)
        assertEquals(ExperienceMode.FOCUSED, failClosed.profile.effectiveExperienceMode())
    }
}
