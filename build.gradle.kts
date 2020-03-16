plugins {
    kotlin("jvm") version "1.3.70"
    application
}

group = "maartyl"
version = "1.0-SNAPSHOT"


val ktor_version = "1.3.1"

repositories {
    mavenCentral()
    jcenter()
}

application {
    mainClassName = "MainKt"
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("com.squareup.okhttp3:okhttp:4.4.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.3")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.10.+")

    implementation("io.ktor:ktor-server-netty:$ktor_version")
    implementation("io.ktor:ktor-websockets:$ktor_version")
    implementation("io.ktor:ktor-html-builder:$ktor_version")
    //compile("ch.qos.logback:logback-classic:1.2.3")

    // https://mvnrepository.com/artifact/org.slf4j/slf4j-api
    //compile(group = "org.slf4j", name = "slf4j-api", version = "1.7.25")

    val kotlinx_html_version = "0.7.1"

    // include for server side
    implementation("org.jetbrains.kotlinx:kotlinx-html-jvm:${kotlinx_html_version}")

}


tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "1.8"
    }
}