buildscript {
    dependencies {
        classpath(libs.kotlin.gradle)
    }
}

plugins {
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.metro) apply false
}
