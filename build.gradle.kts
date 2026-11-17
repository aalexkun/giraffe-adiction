// Top-level build file. Kotlin itself is compiled by AGP 9's built-in Kotlin support, so only the
// Compose compiler plugin and KSP are declared here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
