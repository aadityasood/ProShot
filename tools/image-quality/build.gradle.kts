plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    applicationName = "proshot-image-quality"
    mainClass.set("com.proshot.tools.imagequality.MainKt")
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
