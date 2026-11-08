plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.teum.notificationqueue"
}

dependencies {
    api(project(":core-dli"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":vehicle-signal"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}

android.sourceSets["test"].resources.srcDir(rootProject.file("scenarios"))
