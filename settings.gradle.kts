pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Pleos Connect SDK 저장소 (공식 문서: Vehicle/project-setup, TextToSpeech/importing-sdk).
        // ai.pleos.* 그룹만 여기서 받도록 제한한다.
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content { includeGroupByRegex("ai\\.pleos\\..*") }
        }
    }
}

rootProject.name = "teum"

include(":core-dli", ":vehicle-signal", ":navi-signal", ":voice-gate", ":notification-queue", ":passenger-cue", ":load-aware-api", ":app", ":sample-client")
