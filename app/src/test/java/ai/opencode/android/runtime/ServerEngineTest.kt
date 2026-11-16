package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerEngineTest {
    @Test
    fun v2_uses_the_api_info_health_route_and_version_marker() {
        assertEquals("/api/info", ServerEngine.V2.healthPath)
        assertEquals("\"version\"", ServerEngine.V2.healthMarker)
    }

    @Test
    fun the_v2_asset_name_matches_the_probe_and_the_build_script() {
        assertEquals("node26-v2.zip", ServerEngine.V2_ASSET)
    }

    @Test
    fun the_v2_version_is_the_pinned_release() {
        assertEquals("2.2.5", ServerEngine.V2_VERSION)
    }
}
