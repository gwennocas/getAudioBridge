package app.getaudiobridge.output

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun comparesNumericStableVersionsAndRejectsPrereleases() {
        assertTrue(UpdatePolicy.newer("v0.10.0", "0.2.2"))
        assertFalse(UpdatePolicy.newer("0.2.2", "0.2.2"))
        assertFalse(UpdatePolicy.newer("0.2.1", "0.2.2"))
        assertFalse(UpdatePolicy.newer("1.0.0-beta", "0.2.2"))
    }
    @Test fun pinsRepositoryAssetAndDigest() {
        val name = "getAudioBridge_0.2.2_android-arm64.apk"
        val url = "https://github.com/gwennocas/getAudioBridge/releases/download/v0.2.2/$name"
        val digest = "sha256:" + "a".repeat(64)
        assertTrue(UpdatePolicy.trustedAsset("v0.2.2", name, url, digest))
        assertFalse(UpdatePolicy.trustedAsset("v0.2.2", name, url.replace("gwennocas", "someone"), digest))
        assertFalse(UpdatePolicy.trustedAsset("v0.2.2", name, url.replace("https:", "http:"), digest))
        assertFalse(UpdatePolicy.trustedAsset("v0.2.2", name, "$url?redirect=evil", digest))
        assertFalse(UpdatePolicy.trustedAsset("v0.2.2", name, url, ""))
    }
}
