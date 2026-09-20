package com.audioape.core.download

/**
 * Strict, deterministic v1 manifest encoding for multipart download archives. Deliberately NOT
 * JSON: zero dependencies, grammar-enforced filename safety (parts are restricted to the safe
 * charset by the parser), and byte-exact deterministic output for hashing in tests.
 *
 * Format (line oriented, UTF-8, `\n`):
 * ```
 * # audioape download manifest v1
 * book-id=<canonical uuid>
 * title=<url-escaped title>
 * part-count=N
 * part|<order>|<safe-filename>|<sizeBytes>|<sha256hex>
 * ...
 * ```
 * `title` escapes `%` as `%25`, newline as `%0A`, `=` as `%3D`.
 */
object DownloadManifestCodec {
    const val HEADER = "# audioape download manifest v1"

    fun encode(manifest: DownloadManifest): String =
        buildString {
            appendLine(HEADER)
            appendLine("book-id=${manifest.bookId}")
            appendLine("title=${escape(manifest.displayTitle)}")
            appendLine("part-count=${manifest.parts.size}")
            manifest.parts.sortedBy { it.order }.forEach { part ->
                appendLine("part|${part.order}|${part.fileName}|${part.sizeBytes}|${part.sha256Hex}")
            }
        }

    fun decode(text: String): DownloadManifest {
        val lines = text.split('\n').filter { it.isNotBlank() }
        require(lines.size >= 4) { "manifest too short" }
        require(lines[0] == HEADER) { "manifest header mismatch" }
        val fields =
            lines
                .drop(1)
                .take(3)
                .map { it.split('=', limit = 2) }
                .associate { it[0] to it[1] }
        require(fields.containsKey("book-id") && fields.containsKey("title") && fields.containsKey("part-count")) {
            "manifest missing required fields"
        }
        val partCount =
            fields["part-count"]!!.toIntOrNull()
                ?: throw IllegalArgumentException("manifest part-count invalid")
        require(partCount >= 1 && partCount <= ArchiveSafetyLimits.MAX_PART_COUNT) { "manifest part-count out of range" }
        require(lines.size == 4 + partCount) { "manifest part-count does not match part lines" }

        val parts =
            lines.drop(4).map { line ->
                val cols = line.split('|')
                require(cols.size == 5 && cols[0] == "part") { "malformed part line" }
                val order = cols[1].toIntOrNull() ?: throw IllegalArgumentException("malformed part order")
                PartDescriptor(
                    order = order,
                    fileName = cols[2],
                    sizeBytes = cols[3].toLongOrNull() ?: throw IllegalArgumentException("malformed part size"),
                    sha256Hex = cols[4],
                )
            }
        return DownloadManifest(
            bookId = fields["book-id"]!!,
            displayTitle = unescape(fields["title"]!!),
            parts = parts,
        )
    }

    internal fun escape(value: String): String = value.replace("%", "%25").replace("\n", "%0A").replace("=", "%3D")

    internal fun unescape(value: String): String =
        value.replace(Regex("%(25|3D|0A)")) { match ->
            when (match.groupValues[1]) {
                "25" -> "%"
                "3D" -> "="
                else -> "\n"
            }
        }
}
