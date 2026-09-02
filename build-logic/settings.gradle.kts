// TRAP 1 of 3 — this is an INCLUDED BUILD, so it has its own settings file and its own
// repositories. It does not inherit the root build's dependencyResolutionManagement.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // The catalog has to be imported explicitly for build-logic's OWN build file to use
    // `libs.` accessors. Note this does NOT make the catalog visible inside the
    // precompiled script plugin — see TRAP 3.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
