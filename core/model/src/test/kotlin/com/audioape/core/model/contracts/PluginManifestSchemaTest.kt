package com.audioape.core.model.contracts

import com.networknt.schema.Error
import com.networknt.schema.Schema
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.Assert
import org.junit.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path

/** AA-017: deterministic, pure-JVM contract tests for the v0 plugin manifest schema. */
class PluginManifestSchemaTest {
    private val schemaJson: JsonNode = readJson("contracts/plugin-manifest.schema.json")
    private val schemaId: String = schemaJson.get("\$id").stringValue()
    private val schema: Schema =
        SchemaRegistry
            .builder()
            .schemas(mapOf(schemaId to schemaJson.toString()))
            .schemaLoader { it.fetchRemoteResources(false) }
            .defaultDialectId(SpecificationVersion.DRAFT_2020_12.getDialectId())
            .build()
            .getSchema(SchemaLocation.of(schemaId))

    @Test
    fun `valid fixture demo plugin passes the schema`() {
        assertValid("contracts/fixture-demo-plugin.json")
    }

    @Test
    fun `valid http operation manifest with one literal https host passes`() {
        assertValid("contracts/fixture-demo-plugin-http.json")
    }

    @Test
    fun `rejects http scheme host`() {
        assertInvalid(
            replace(
                """permissions""",
                """{"allowedHosts": ["http://example.com"], "requiresOwnVault": false, "allowBackgroundRefresh": false}""",
            ),
        )
    }

    @Test
    fun `rejects wildcard host`() {
        assertInvalid(
            replace(
                """permissions""",
                """{"allowedHosts": ["*.example.com"], "requiresOwnVault": false, "allowBackgroundRefresh": false}""",
            ),
        )
    }

    @Test
    fun `rejects host with port`() {
        assertInvalid(
            replace(
                """permissions""",
                """{"allowedHosts": ["api.example.com:8443"], "requiresOwnVault": false, "allowBackgroundRefresh": false}""",
            ),
        )
    }

    @Test
    fun `rejects host with path`() {
        assertInvalid(
            replace(
                """permissions""",
                """{"allowedHosts": ["example.com/api"], "requiresOwnVault": false, "allowBackgroundRefresh": false}""",
            ),
        )
    }

    @Test
    fun `rejects ip literal host`() {
        assertInvalid(
            replace("""permissions""", """{"allowedHosts": ["127.0.0.1"], "requiresOwnVault": false, "allowBackgroundRefresh": false}"""),
        )
    }

    @Test
    fun `rejects private localhost host`() {
        assertInvalid(
            replace("""permissions""", """{"allowedHosts": ["localhost"], "requiresOwnVault": false, "allowBackgroundRefresh": false}"""),
        )
    }

    @Test
    fun `rejects punycode alias host`() {
        assertInvalid(
            replace(
                """permissions""",
                """{"allowedHosts": ["xn--80ak6aa92e.com"], "requiresOwnVault": false, "allowBackgroundRefresh": false}""",
            ),
        )
    }

    @Test
    fun `rejects capability not in enum`() {
        assertInvalid(replace("""capabilities""", """["catalog", "frobnicate"]"""))
    }

    @Test
    fun `rejects unknown host operation`() {
        assertInvalid(replace("""operations""", """[{"capability": "catalog", "hostOperation": "http.eval.v0"}]"""))
    }

    @Test
    fun `rejects operation with capability not in enum`() {
        assertInvalid(replace("""operations""", """[{"capability": "teleport", "hostOperation": "fixture.catalog.v0"}]"""))
    }

    @Test
    fun `rejects http operation without a host`() {
        assertInvalid(replace("""operations""", """[{"capability": "catalog", "hostOperation": "http.get_json.v0"}]"""))
    }

    @Test
    fun `operation host not granted in permissions is a parse-time battery rule`() {
        // Cross-array invariant: a JSON schema cannot tie operations[].host to
        // permissions.allowedHosts (documented in the capability table, section 3). The schema
        // still accepts it; the deterministic battery rule must reject it (AA-023 host loader).
        val manifest: JsonNode =
            replace(
                """operations""",
                """[{"capability": "catalog", "hostOperation": "http.get_json.v0", "host": "not-granted.example.com", "pathTemplate": "/api/v1/search"}]""",
            )
        Assert.assertTrue("schema-level shape is legal for a cross-array check", schema.validate(manifest).isEmpty())
        val violations: List<String> = batteryViolations(manifest)
        Assert.assertTrue(
            "expected battery to flag host not in allowedHosts, got: " + violations.joinToString(),
            violations.any {
                it.contains("allowedHosts")
            },
        )
    }

    @Test
    fun `rejects operation host with port`() {
        assertInvalid(
            replace(
                """operations""",
                """[{"capability": "catalog", "hostOperation": "http.get_json.v0", "host": "catalog.audioape.example:8443", "pathTemplate": "/api/v1/search"}]""",
            ),
        )
    }

    @Test
    fun `rejects path template with query string`() {
        assertInvalid(
            replace(
                """operations""",
                """[{"capability": "catalog", "hostOperation": "http.get_json.v0", "host": "catalog.audioape.example", "pathTemplate": "/api/search?q="}]""",
            ),
        )
    }

    @Test
    fun `rejects path template with dotdot`() {
        assertInvalid(
            replace(
                """operations""",
                """[{"capability": "catalog", "hostOperation": "http.get_json.v0", "host": "catalog.audioape.example", "pathTemplate": "/../secrets"}]""",
            ),
        )
    }

    @Test
    fun `rejects response payload cap above hard ceiling`() {
        assertInvalid(
            replace(
                """operations""",
                """[{"capability": "catalog", "hostOperation": "http.get_json.v0", "host": "catalog.audioape.example", "pathTemplate": "/api/v1/search", "maxResponseBytes": 16777216}]""",
            ),
        )
    }

    @Test
    fun `rejects zero response payload cap`() {
        assertInvalid(
            replace(
                """operations""",
                """[{"capability": "catalog", "hostOperation": "http.get_json.v0", "host": "catalog.audioape.example", "pathTemplate": "/api/v1/search", "maxResponseBytes": 0}]""",
            ),
        )
    }

    @Test
    fun `rejects timeout above hard ceiling`() {
        assertInvalid(
            replace(
                """operations""",
                """[{"capability": "catalog", "hostOperation": "http.get_json.v0", "host": "catalog.audioape.example", "pathTemplate": "/api/v1/search", "timeoutMs": 60000}]""",
            ),
        )
    }

    @Test
    fun `rejects extra top level property`() {
        assertInvalid(replace("""executable""", """"\/bin\/sh""""))
    }

    @Test
    fun `rejects extra permission property`() {
        assertInvalid(
            replace(
                """permissions""",
                """{"allowedHosts": ["catalog.audioape.example"], "requiresOwnVault": false, "allowBackgroundRefresh": false, "arbitraryFilesystem": true}""",
            ),
        )
    }

    @Test
    fun `settings default that names a host is a parse-time battery rule`() {
        // Content policy ("no default that enables network"): cannot be schema-level because the
        // default is a plain bounded string. The battery rule rejects network-bearing defaults.
        val manifest: JsonNode = replace("""settingsSchema""", """{"fields": {"host": {"kind": "text", "default": "evil.example.com"}}}""")
        Assert.assertTrue("settings default string is schema-legal", schema.validate(manifest).isEmpty())
        val violations: List<String> = batteryViolations(manifest)
        Assert.assertTrue(
            "expected battery to flag network default, got: " + violations.joinToString(),
            violations.any { it.contains("default") },
        )
    }

    @Test
    fun `rejects credential with arbitrary extra property`() {
        assertInvalid(replace("""credentialSchema""", """{"fields": {"token": {"kind": "text", "exfil": "/tmp/token"}}}"""))
    }

    @Test
    fun `rejects settings schema with too many fields`() {
        assertInvalid(
            replace(
                """settingsSchema""",
                """{"fields": {"a": {"kind": "text"}, "b": {"kind": "text"}, "c": {"kind": "text"}, "d": {"kind": "text"}, "e": {"kind": "text"}, "f": {"kind": "text"}, "g": {"kind": "text"}, "h": {"kind": "text"}, "i": {"kind": "text"}}}""",
            ),
        )
    }

    @Test
    fun `rejects schemaVersion other than zero`() {
        assertInvalid(replace("""schemaVersion""", """1"""))
    }

    private fun assertValid(relativePath: String) {
        val instance: JsonNode = readJson(relativePath)
        val errors: List<Error> = schema.validate(instance)
        Assert.assertTrue(
            "expected '$relativePath' to validate, got: " + formatMessages(errors),
            errors.isEmpty(),
        )
    }

    private fun assertInvalid(instance: JsonNode) {
        val errors: List<Error> = schema.validate(instance)
        Assert.assertFalse(
            "expected manifest to be rejected, got: " + formatMessages(errors),
            errors.isEmpty(),
        )
    }

    private fun replace(
        pointer: String,
        replacement: String,
    ): JsonNode {
        val clone: ObjectNode = fixture().deepCopy() as ObjectNode
        clone.set(pointer, ObjectMapper().readTree(replacement))
        return clone
    }

    private fun fixture(): JsonNode = readJson("contracts/fixture-demo-plugin.json")

    private fun readJson(relativePath: String): JsonNode {
        val resolved: Path = locate(relativePath)
        return ObjectMapper().readTree(resolved.toFile())
    }

    /** JVM tests may run from the module dir, repo root, or the build dir: walk known bases. */
    private fun locate(relativePath: String): Path {
        val candidates: List<Path> =
            listOf(
                Path.of("."),
                Path.of("../.."),
                Path.of("../../.."),
                Path.of(Path.of("").toAbsolutePath().toString(), "core", "model"),
            ).map { it.resolve(relativePath) }
        val found: Path? = candidates.firstOrNull { Files.isReadable(it) }
        Assert.assertNotNull(
            "fixture not found: $relativePath (searched " +
                candidates.map { it.toString() }.joinToString(", ") + ")",
            found,
        )
        return found as Path
    }

    private fun formatMessages(errors: List<Error>): String =
        errors
            .map { it.getMessage() }
            .take(6)
            .joinToString(separator = "; ")

    /**
     * Parse-time battery rules not expressible in JSON-schema (cross-array pairing, content
     * policy). The AA-023 host loader enforces the same rules at runtime; this method keeps them
     * deterministic in JVM tests today.
     */
    private fun batteryViolations(manifest: JsonNode): List<String> {
        val violations: MutableList<String> = mutableListOf()
        val allowedHosts: List<String> =
            manifest
                .path("permissions")
                .path("allowedHosts")
                .toList()
                .map { it.stringValue("") }
        val operations: List<JsonNode> = manifest.path("operations").toList()
        for (op in operations) {
            val capability: String = op.path("capability").stringValue("")
            val hostOperation: String = op.path("hostOperation").stringValue("")
            val host: String = op.path("host").stringValue("")
            if ((hostOperation == "http.get_json.v0" && capability == "authorization") ||
                (hostOperation == "http.get_json.v0" && capability == "acquisition_resolver")
            ) {
                violations.add("http.get_json.v0 is not allowed for capability '$capability'")
            }
            if (hostOperation.startsWith("http.") && host !in allowedHosts) {
                violations.add("operation host '$host' not in permissions.allowedHosts")
            }
        }
        val settingsFields: JsonNode = manifest.path("settingsSchema").path("fields")
        if (!settingsFields.isMissingNode() && !settingsFields.isNull()) {
            for (field in settingsFields.properties()) {
                val fieldName: String = field.key
                val defaultValue: String = field.value.path("default").stringValue("")
                val looksLikeHost: Boolean = defaultValue.contains(".") && !defaultValue.startsWith(".")
                if (defaultValue.startsWith("http") || looksLikeHost) {
                    violations.add("settings field '$fieldName' default '$defaultValue' must not name a network host")
                }
            }
        }
        return java.util.ArrayList(violations)
    }
}
