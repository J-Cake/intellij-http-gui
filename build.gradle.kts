plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":core"))
    intellijPlatform {
        // An unpacked IDE given via -PplatformLocalPath=... is used instead of downloading one.
        val localPlatform = providers.gradleProperty("platformLocalPath")
        if (localPlatform.isPresent) local(localPlatform) else intellijIdea(providers.gradleProperty("platformVersion"))
    }
}

intellijPlatform {
    // No GUI forms are used, and skipping instrumentation avoids downloading the Java compiler from JetBrains.
    instrumentCode = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
}
