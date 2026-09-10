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
    // One JNI module per language per platform (sdl-kmp layout): packs the
    // native library of <lang> built for <platform> into
    // treesitter-languages-<lang>-kmp-jni-<platform>.
    listOf(
        "darwin-aarch64",
        "darwin-x86_64",
        "linux-x86_64",
        "linux-aarch64",
        "windows-x86_64"
    ).forEach { platform ->
        include(":jni:${lang.name}-$platform")
    }
}
