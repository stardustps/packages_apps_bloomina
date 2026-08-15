pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // tribalfs SESL8 / OneUI 8 live on GitHub Packages (Maven) and REQUIRE auth,
        // even though the packages are public. Provide a GitHub username + a PAT with
        // the read:packages scope. Local: ~/.gradle/gradle.properties (gpr.user / gpr.key).
        // CI: GPR_USER / GPR_TOKEN env vars (from Actions secrets).
       maven { url = uri("https://jitpack.io") }
        val ghUser = providers.gradleProperty("gpr.user")
            .orElse(providers.environmentVariable("GPR_USER"))
        val ghToken = providers.gradleProperty("gpr.key")
            .orElse(providers.environmentVariable("GPR_TOKEN"))
        listOf("sesl-androidx", "sesl-material-components-android", "oneui-design").forEach { repo ->
            maven {
                url = uri("https://maven.pkg.github.com/tribalfs/$repo")
                credentials {
                    username = ghUser.orNull ?: ""
                    password = ghToken.orNull ?: ""
                }
            }
        }
    }
}
rootProject.name = "skynight"
include(":app")
