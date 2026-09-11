pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "tree-sitter-languages-kmp"

include(":example")

file("languages").listFiles { file -> file.isDirectory }?.forEach { lang ->
    include(":languages:${lang.name}")
}

// JNI modules are generated at configuration time (one project per language
// per platform, sdl-kmp layout). Each is a real project with its own build
// script, so com.vanniktech.maven.publish creates its `maven` publication
// normally; the sources live under build/ (gitignored) instead of polluting
// the repository with 170 empty module directories.
val jniModulesDir = rootDir.resolve("build/jni-modules")
val jniPlatforms = listOf(
    "darwin-aarch64", "darwin-x86_64",
    "linux-x86_64", "linux-aarch64", "windows-x86_64",
)
file("languages").listFiles { file -> file.isDirectory }?.forEach { lang ->
    jniPlatforms.forEach { platform ->
        val name = "${lang.name}-$platform"
        val dir = jniModulesDir.resolve(name)
        dir.mkdirs()
        val buildFile = dir.resolve("build.gradle.kts")
        if (!buildFile.exists()) {
            buildFile.writeText(
                """
                plugins {
                    id("jni-language")
                }
                """.trimIndent() + "\n"
            )
        }
        include(":jni:$name")
        project(":jni:$name").projectDir = dir
    }
}
// The implicit :jni parent also needs a directory.
project(":jni").projectDir = jniModulesDir
jniModulesDir.mkdirs()
