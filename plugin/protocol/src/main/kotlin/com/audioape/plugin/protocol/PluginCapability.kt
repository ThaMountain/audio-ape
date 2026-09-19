package com.audioape.plugin.protocol

/**
 * Closed set of capabilities a v0 Audio Ape plugin may declare (AA-017 schema enum).
 *
 * Mirrors `contracts/plugin-manifest.schema.json` `capabilities[].enum` one-to-one so the
 * host boundary never works with free-form strings. Capability gating pairs each operation
 * with exactly the capability that may call it; see the pairing table §3 of
 * `docs/architecture/plugin-capability-table.md`.
 */
enum class PluginCapability {
    CATALOG,
    METADATA,
    SOURCE_SEARCH,
    AUTHORIZATION,
    ACQUISITION_RESOLVER,
}
