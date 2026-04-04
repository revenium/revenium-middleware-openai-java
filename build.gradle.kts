plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "io.revenium.metering"
version = "0.1.6"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

repositories {
    mavenCentral()
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    // Sign only when signing credentials are available (GPG key configured for Maven Central publishing)
    // Local development and publishToMavenLocal skip signing automatically when no key is present
    val signingKey = providers.gradleProperty("signing.key").orNull
        ?: System.getenv("GPG_SIGNING_KEY")
    if (signingKey != null || providers.gradleProperty("signing.keyId").isPresent) {
        signAllPublications()
    }

    coordinates("io.revenium.metering", "revenium-middleware-openai-java", version.toString())

    pom {
        name.set("Revenium Middleware for OpenAI Java SDK")
        description.set("Transparent metering middleware for the OpenAI Java SDK - wraps client services to automatically report AI usage metrics to Revenium.")
        inceptionYear.set("2026")
        url.set("https://github.com/revenium/revenium-middleware-openai-java/")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("revenium")
                name.set("Revenium")
                url.set("https://github.com/revenium/")
            }
        }

        scm {
            url.set("https://github.com/revenium/revenium-middleware-openai-java/")
            connection.set("scm:git:git://github.com/revenium/revenium-middleware-openai-java.git")
            developerConnection.set("scm:git:ssh://git@github.com/revenium/revenium-middleware-openai-java.git")
        }
    }
}

dependencies {
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.0")
    compileOnly("com.openai:openai-java:4.20.0")

    testImplementation("com.openai:openai-java:4.20.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.3")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.wiremock:wiremock:3.13.2")
    testImplementation("org.mockito:mockito-core:5.23.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.23.0")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
