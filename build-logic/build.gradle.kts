plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.android.gradle)
    implementation(libs.vanniktech.maven.publish)
}

gradlePlugin {
    plugins {
        create("tree-sitter-grammar") {
            id = "tree-sitter-grammar"
            implementationClass = "cn.enaium.treesitter.languages.plugin.GrammarPlugin"
        }
    }
}
