plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.teum.voicegate"
}

dependencies {
    api(project(":core-dli"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // Gleo AI 음성 스택(Pleos Connect SDK): TTS / STT / LLM. 모두 ON_DEVICE 모드로만 쓴다.
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.llm)

    testImplementation(project(":vehicle-signal"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
}

android.sourceSets["test"].resources.srcDir(rootProject.file("scenarios"))
