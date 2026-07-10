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
    }
}

rootProject.name = "Sonario"
include(":app")
// The llama.cpp Android binding is added as a local module. See README:
// clone llama.cpp and point this at examples/llama.android/llama
// include(":llama")
// project(":llama").projectDir = File("/path/to/llama.cpp/examples/llama.android/llama")
