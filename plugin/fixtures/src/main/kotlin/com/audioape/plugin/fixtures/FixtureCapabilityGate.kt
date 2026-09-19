package com.audioape.plugin.fixtures

import com.audioape.plugin.protocol.PluginCapability
import com.audioape.plugin.protocol.PluginErrorCode
import com.audioape.plugin.protocol.PluginResult
import com.audioape.plugin.protocol.pluginRejected

/**
 * Runtime pairing gate for the fixture source, mirroring the capability table §3
 * (`declared capability → allowed host operation`).
 *
 * The fixture modules expose operations only through the declared capability: a caller
 * may invoke `fixture.catalog.v0` only when it holds [PluginCapability.CATALOG], and so
 * on. Calling an operation under the wrong capability is rejected with a typed
 * [PluginRejection], never an exception.
 */
object FixtureCapabilityGate {
    /**
     * Returns the fixed capability for one operation, or null if the pairing is not
     * declared (nothing is implied).
     */
    fun capabilityFor(hostOperation: String): PluginCapability? =
        if (hostOperation == "fixture.catalog.v0") {
            PluginCapability.CATALOG
        } else if (hostOperation == "fixture.sources.v0") {
            PluginCapability.SOURCE_SEARCH
        } else if (hostOperation == "fixture.resolve.v0") {
            PluginCapability.ACQUISITION_RESOLVER
        } else {
            null
        }

    /**
     * Rejects a call whose held capability does not pair with the requested primitive.
     * Fixture `fixture.*.v0` primitives are not paired with `metadata`,
     * `authorization`, or any other capability.
     */
    fun <T> requirePairing(
        held: PluginCapability,
        hostOperation: String,
    ): PluginResult<T>? {
        val expected = capabilityFor(hostOperation)
        if (expected != held) {
            return pluginRejected<T>(
                PluginErrorCode.CAPABILITY_MISMATCH,
                "capability '$held' cannot invoke '$hostOperation'",
            )
        }
        return null
    }
}
