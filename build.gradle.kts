plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}

allprojects {
    group = "cn.enaium.treesitter"
    // No root version: each language module sets its own from the grammar
    // repository tag (build-logic tree-sitter-language.gradle.kts).

    repositories {
        mavenCentral()
        google()
    }
}

