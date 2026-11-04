package com.akari.ppx.utils

import org.junit.Assert.*
import org.junit.Test

class AssistantUpdatesTest {
    @Test fun comparesNumericVersions() {
        assertTrue(AssistantUpdates.isNewer("v0.0.10", "0.0.9"))
        assertTrue(AssistantUpdates.isNewer("v0.1.0", "0.0.4"))
        assertFalse(AssistantUpdates.isNewer("v0.0.4", "0.0.4"))
        assertFalse(AssistantUpdates.isNewer("v0.0.3", "0.0.4"))
        assertFalse(AssistantUpdates.isNewer("v0.0.4.0", "0.0.4"))
    }

    @Test fun ignoresNonReleaseVersionTags() {
        assertFalse(AssistantUpdates.isNewer("v0.1.0-beta", "0.0.4"))
        assertFalse(AssistantUpdates.isNewer("latest", "0.0.4"))
        assertFalse(AssistantUpdates.isNewer("", "0.0.4"))
    }

    @Test fun readsModuleRepoReleaseNames() {
        // Xposed-Modules-Repo 的发布名即纯版本号，优先于标签。
        assertEquals("0.0.7", AssistantUpdates.releaseVersion("20261010-0.0.7", "0.0.7"))
    }

    @Test fun readsModuleRepoTagFormats() {
        assertEquals("0.0.7", AssistantUpdates.releaseVersion("20261010-0.0.7", null))
        assertEquals("0.0.7", AssistantUpdates.releaseVersion("v0.0.7", null))
        assertEquals("0.0.7", AssistantUpdates.releaseVersion("0.0.7", ""))
    }

    @Test fun rejectsNonVersionTags() {
        assertNull(AssistantUpdates.releaseVersion("latest", null))
        assertNull(AssistantUpdates.releaseVersion("20261010-beta", null))
        assertNull(AssistantUpdates.releaseVersion("", null))
    }
}
