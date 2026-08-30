package com.fabiosilva.ecosdeeldra

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationPolicyTest {
    @Test
    fun allowsOnlyPackagedGamePages() {
        assertTrue(
            NavigationPolicy.isAllowedTopLevel(
                "https://appassets.androidplatform.net/assets/game/index.html",
            ),
        )
        assertTrue(
            NavigationPolicy.isAllowedTopLevel(
                "https://appassets.androidplatform.net/assets/game/help.html",
            ),
        )
        assertFalse(NavigationPolicy.isAllowedTopLevel("https://example.com/"))
        assertFalse(
            NavigationPolicy.isAllowedTopLevel(
                "file:///android_asset/game/index.html",
            ),
        )
    }

    @Test
    fun rejectsHostPrefixAndTraversalLookalikes() {
        assertFalse(
            NavigationPolicy.isAllowedTopLevel(
                "https://appassets.androidplatform.net.evil.example/assets/game/index.html",
            ),
        )
        assertFalse(
            NavigationPolicy.isAllowedTopLevel(
                "https://appassets.androidplatform.net/assets/not-game/index.html",
            ),
        )
    }

    @Test
    fun lifecycleBridgeDispatchesDistinctPauseAndResumeEvents() {
        assertEquals(
            "window.dispatchEvent(new Event('zombie-zone-app-pause'))",
            GameLifecycleBridge.scriptFor(active = false),
        )
        assertEquals(
            "window.dispatchEvent(new Event('zombie-zone-app-resume'))",
            GameLifecycleBridge.scriptFor(active = true),
        )
    }
}
