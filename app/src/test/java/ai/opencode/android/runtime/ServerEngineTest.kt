package ai.opencode.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerEngineTest {
    @Test
    fun v2_only_when_both_node26_binary_and_bundle_asset_are_present() {
        assertEquals(ServerEngine.V2, ServerEngine.select(hasNode26Binary = true, hasV2Asset = true))
        assertEquals(ServerEngine.V1, ServerEngine.select(hasNode26Binary = true, hasV2Asset = false))
        assertEquals(ServerEngine.V1, ServerEngine.select(hasNode26Binary = false, hasV2Asset = true))
        assertEquals(ServerEngine.V1, ServerEngine.select(hasNode26Binary = false, hasV2Asset = false))
    }

    @Test
    fun v1_keeps_the_global_health_route_and_v2_uses_api_info() {
        assertEquals("/global/health", ServerEngine.V1.healthPath)
        assertEquals("\"healthy\"", ServerEngine.V1.healthMarker)
        assertEquals("/api/info", ServerEngine.V2.healthPath)
        assertEquals("\"version\"", ServerEngine.V2.healthMarker)
    }

    @Test
    fun the_v2_asset_name_matches_the_probe_and_the_build_script() {
        assertEquals("node26-v2.zip", ServerEngine.V2_ASSET)
    }
}
