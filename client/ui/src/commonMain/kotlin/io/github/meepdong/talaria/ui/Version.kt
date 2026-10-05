package io.github.meepdong.talaria.ui

/** This build's version. Equal to `talaria.version` in client/gradle.properties: the ui build checks. */
const val TALARIA_VERSION = "0.2.0-beta.3"

private val RELEASE = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$""")

/**
 * Android's version code for a release version: MAJOR*1000000 + MINOR*10000 + PATCH*100 + N for `beta.N`,
 * and + 99 for a stable release (PROCESS.md, Releases; androidApp/build.gradle.kts uses the same rule).
 * Null when [version] isn't a release version.
 */
fun versionCodeOf(version: String): Long? {
    val (major, minor, patch, beta) = RELEASE.matchEntire(version)?.destructured ?: return null
    val n = if (beta.isEmpty()) 99 else beta.toInt().takeIf { it in 1..98 } ?: return null
    if (minor.toInt() > 99 || patch.toInt() > 99) return null
    return major.toLong() * 1_000_000 + minor.toInt() * 10_000 + patch.toInt() * 100 + n
}
