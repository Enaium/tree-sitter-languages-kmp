@file:Suppress("DEPRECATION")

import org.gradle.api.tasks.Copy

/**
 * Convention for the dynamically generated per-language per-platform JNI
 * projects (`build/jni-modules/<lang>-<platform>`, registered in settings).
 *
 * Each is a plain java-library whose `maven` publication is created by
 * com.vanniktech.maven.publish from `components.java`; the native library
 * that `:languages:<lang>:buildJni` built for this project's platform rides
 * in `src/main/resources/lib/<os>/<arch>/` via processResources and lands in
 * the jar as `lib/<os>/<arch>/libktreesitter-<lang>.<ext>`.
 *
 * Coordinates: cn.enaium.treesitter:treesitter-languages-<lang>-kmp-jni-<platform>
 * Version: grammar repository tag + .1 release suffix.
 */
plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

val knownPlatforms = listOf(
    "darwin-aarch64", "darwin-x86_64",
    "linux-x86_64", "linux-aarch64", "windows-x86_64",
)

val platform: String = knownPlatforms.firstOrNull { name.endsWith("-$it") }
    ?: error("Unknown JNI platform module: $name")
val langName: String = name.removeSuffix("-$platform")

val (jniOs, jniArch) = when (platform) {
    "darwin-aarch64" -> "macos" to "aarch64"
    "darwin-x86_64" -> "macos" to "x64"
    "linux-x86_64" -> "linux" to "x64"
    "linux-aarch64" -> "linux" to "aarch64"
    "windows-x86_64" -> "windows" to "x64"
    else -> error("Unknown JNI platform: $platform")
}

group = "cn.enaium.treesitter"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Version mirrors the language module: grammar repository tag + .1.
// Resolved directly from the submodule so it does not depend on the
// evaluation order of the language project.
val grammarDir = rootProject.layout.projectDirectory.dir("languages/$langName/tree-sitter-$langName").asFile

fun runGit(vararg args: String): Pair<Int, String> {
    val process = ProcessBuilder(*args).directory(grammarDir).start()
    val out = process.inputStream.bufferedReader().readText().trim()
    return process.waitFor() to out
}

var grammarTag: String? = runGit("git", "describe", "--tags", "--abbrev=0").let { (code, tag) ->
    if (code == 0 && tag.isNotEmpty()) tag.removePrefix("v") else null
}
if (grammarTag == null) {
    runGit("git", "fetch", "--unshallow", "--tags", "--quiet", "origin")
    grammarTag = runGit("git", "describe", "--tags", "--abbrev=0").let { (code, tag) ->
        if (code == 0 && tag.isNotEmpty()) tag.removePrefix("v") else null
    }
}
val publishVersion: String = if (langName == "smali") {
    "1.0.0.1"
} else {
    check(grammarTag != null) { "Cannot determine version for grammar $langName (git describe failed)" }
    "$grammarTag.1"
}
version = publishVersion

val langJniLibs = rootProject.layout.projectDirectory.dir("languages/$langName/build/jni-libs")

tasks.named<Copy>("processResources") {
    dependsOn(":languages:$langName:buildJni")
    from(langJniLibs) {
        include("lib/$jniOs/$jniArch/*")
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates(
        "cn.enaium.treesitter",
        "treesitter-languages-$langName-kmp-jni-$platform",
        publishVersion,
    )
    pom {
        name = "tree-sitter-$langName JNI library for $platform"
        description = "Prebuilt JNI shared library for the tree-sitter $langName grammar on $jniOs/$jniArch."
        url = "https://github.com/Enaium/tree-sitter-languages-kmp"
        licenses {
            license {
                name = "MIT License"
                url = "https://spdx.org/licenses/MIT.html"
            }
        }
        developers {
            developer {
                id = "enaium"
                name = "Enaium"
                url = "https://github.com/Enaium"
            }
        }
        scm {
            url = "https://github.com/Enaium/tree-sitter-languages-kmp"
            connection = "scm:git:git@github.com:Enaium/tree-sitter-languages-kmp.git"
            developerConnection = "scm:git:git@github.com:Enaium/tree-sitter-languages-kmp.git"
        }
    }
}