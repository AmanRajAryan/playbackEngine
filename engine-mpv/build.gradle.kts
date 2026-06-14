import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.library")
  id("kotlin-android")
  alias(libs.plugins.vanniktech.maven.publish)
}

android {
  namespace = "aman.playbackengine.enginempv"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
  }
  
  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }

  kotlin {
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_1_8)
    }
  }
}

dependencies {
  implementation(project(":engine-core"))
  
  // NOTE: Native MPV binaries must be provided manually by the app developer.
  // Download the AAR from the GitHub Releases page and place it in this folder.
  compileOnly(files("libs/mpv-android-lib-v0.0.1.aar"))
  
  // Media3 dependencies
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.ui)
  
  // Core Android dependencies
  implementation("androidx.annotation:annotation:1.7.0")
  implementation(libs.kotlinx.coroutines.android)
}

mavenPublishing {
    coordinates("io.github.amanrajaryan", "playback-engine-mpv", "1.0.0")

    // Disable Javadoc to prevent Dokka from crashing on the native AAR
    configure(com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
        variant = "release",
        sourcesJar = true,
        publishJavadocJar = false
    ))

    pom {
        name.set("playback-engine-mpv")
        description.set("libmpv-based engine implementation. Requires external native AAR.")
        inceptionYear.set("2026")
        url.set("https://github.com/AmanRajAryan/playbackEngine") 

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("AmanRajAryan") 
                name.set("Aman Raj Aryan")
            }
        }
        scm {
            url.set("https://github.com/AmanRajAryan/playbackEngine")
            connection.set("scm:git:git://github.com/AmanRajAryan/playbackEngine.git")
            developerConnection.set("scm:git:ssh://git@github.com/AmanRajAryan/playbackEngine.git")
        }
    }

    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
}
