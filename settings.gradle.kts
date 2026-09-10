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
    // One JNI module per language (sdl-kmp layout): publishes the five
    // per-platform treesitter-languages-<lang>-kmp-jni-<platform> artifacts.
    include(":jni:${lang.name}")
}
