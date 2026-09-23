// Insta360 Maven 凭据不进仓库：构建时从本地 gradle.properties（~/.gradle/gradle.properties）读取
//   insta360MavenUsername=...
//   insta360MavenPassword=...
val insta360MavenUsername = providers.gradleProperty("insta360MavenUsername")
val insta360MavenPassword = providers.gradleProperty("insta360MavenPassword")
if (!insta360MavenUsername.isPresent || !insta360MavenPassword.isPresent) {
    throw GradleException(
        "缺少 Insta360 Maven 凭据。请在 ~/.gradle/gradle.properties 中配置 " +
            "insta360MavenUsername / insta360MavenPassword（凭据不提交仓库）"
    )
}

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
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
        maven {
            name = "Insta360"
            url = uri("https://androidsdk.insta360.com/repository/maven-public/")
            credentials {
                username = insta360MavenUsername.get()
                password = insta360MavenPassword.get()
            }
        }
    }
}

rootProject.name = "sign"
include(":app")
