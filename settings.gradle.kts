pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rgkit"

// Each SDK lives at <sdk-name>/android/<modulename>. The Gradle project name
// is the kebab-case SDK name, which becomes the published artifactId:
//   io.github.rongo270:exit-reason:<version>
fun sdk(name: String, moduleDir: String) {
    include(":$name")
    project(":$name").projectDir = file("$name/android/$moduleDir")
}

sdk("intent-engine", "intentengine")
sdk("context-moments", "contextmoments")
sdk("screenshot-intelligence", "screenshotiq")
sdk("exit-reason", "exitreason")
sdk("adaptive-ui", "adaptiveui")
sdk("flow-learning", "flowlearning")
sdk("rhythm-engine", "rhythmengine")
sdk("perceived-speed", "perceivedspeed")
sdk("form-sense", "formsense")
sdk("grip-sense", "gripsense")
sdk("discovery-coach", "discoverycoach")
sdk("feature-usage", "featureusage")
sdk("user-memory", "usermemory")
