// Pure Kotlin model of the HTTP Client file format; no IntelliJ dependencies so it can be unit tested quickly.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib"))
    implementation("org.yaml:snakeyaml:2.2")

    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // SampleFilesTest reads the sample files.
    inputs.dir(rootDir.resolve("samples"))
}
