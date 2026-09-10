@file:Suppress("DEPRECATION")

import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension

/**
 * Convention for the per-language JNI modules (`jni/<lang>`).
 *
 * One module per language publishes the five per-platform JNI artifacts
 * `cn.enaium.treesitter:treesitter-languages-<lang>-kmp-jni-<platform>`,
 * each embedding the native library that `:languages:<lang>:buildJni` built
 * for that platform at `lib/<os>/<arch>/libktreesitter-<lang>.<ext>`.
 *
 * Uses plain maven-publish (not com.vanniktech.maven.publish, which refuses
 * publications it did not create) with a manually configured Maven Central
 * repository and PGP signing; task names are
 * `publishJni<Platform>PublicationTo<Repo>Repository` (e.g.
 * `publishJniDarwinAarch64PublicationToMavenCentralRepository`).
 */
plugins {
    `java-library`
    `maven-publish`
    signing
}

val jniPlatforms = listOf(
    "darwin-aarch64" to ("macos" to "aarch64"),
    "darwin-x86_64" to ("macos" to "x64"),
    "linux-x86_64" to ("linux" to "x64"),
    "linux-aarch64" to ("linux" to "aarch64"),
    "windows-x86_64" to ("windows" to "x64"),
)

group = "cn.enaium.treesitter"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Version mirrors the language module: grammar repository tag + .1.
// Resolved directly from the submodule so it does not depend on the
// evaluation order of the language project.
val grammarDir = rootProject.layout.projectDirectory.dir("languages/${project.name}/tree-sitter-${project.name}").asFile

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
val langName: String = name
val publishVersion: String = if (langName == "smali") {
    "1.0.0.1"
} else {
    check(grammarTag != null) { "Cannot determine version for grammar $langName (git describe failed)" }
    "$grammarTag.1"
}
version = publishVersion

val langJniLibs = rootProject.layout.projectDirectory.dir("languages/$langName/build/jni-libs")

fun platformCamel(suffix: String): String =
    suffix.split("-").joinToString("") { it.replaceFirstChar { c -> c.uppercaseChar() } }

val jarTasks = mutableMapOf<String, TaskProvider<Jar>>()
jniPlatforms.forEach { (suffix, osArch) ->
    val (jniOs, jniArch) = osArch
    val jarTask = tasks.register("jniJar${platformCamel(suffix)}", Jar::class.java) {
        group = "publishing"
        description = "Package the $langName native library for $suffix"
        archiveFileName.set("treesitter-languages-$langName-kmp-jni-$suffix-$publishVersion.jar")
        destinationDirectory.set(layout.buildDirectory.dir("libs-jni"))
        dependsOn(":languages:$langName:buildJni")
        from(langJniLibs) {
            include("lib/$jniOs/$jniArch/*")
        }
    }
    jarTasks[suffix] = jarTask
}

val publishing = extensions.getByType<PublishingExtension>()
publishing.publications {
    jniPlatforms.forEach { (suffix, osArch) ->
        val (jniOs, jniArch) = osArch
        create("jni${platformCamel(suffix)}", MavenPublication::class.java) {
            artifactId = "treesitter-languages-$langName-kmp-jni-$suffix"
            version = publishVersion
            artifact(jarTasks.getValue(suffix))
            pom {
                name = "tree-sitter-$langName JNI library for $suffix"
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
    }
}

publishing.repositories {
    maven {
        name = "MavenCentral"
        url = uri("https://central.sonatype.com/api/v1/publisher")
        credentials {
            username = providers.gradleProperty("mavenCentralUsername").getOrElse("")
            password = providers.gradleProperty("mavenCentralPassword").getOrElse("")
        }
    }
}

extensions.configure<SigningExtension>("signing") {
    sign(publishing.publications)
}

// Keep task-name compatibility with the default maven-publish naming
// (publishJni<Platform>PublicationToMavenCentralRepository) — the repo
// created above is named MavenCentral.