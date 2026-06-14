import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.library")
  id("kotlin-android")
  alias(libs.plugins.vanniktech.maven.publish)
}

android {
  namespace = "aman.playbackengine.engineexoplayer"
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
  
  // Media3 dependencies
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.common)
  implementation(libs.androidx.media3.ui)
  
  // Core Android dependencies
  implementation("androidx.annotation:annotation:1.7.0")
  implementation(libs.kotlinx.coroutines.android)
}

mavenPublishing {
    coordinates("io.github.amanrajaryan", "playback-engine-exoplayer", "1.0.2")

    pom {
        name.set("playback-engine-exoplayer")
        description.set("ExoPlayer-based engine implementation for the playback library")
        inceptionYear.set("2026")
        url.set("https://github.com/AmanRajAryan/playbackEngine") 

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("AmanRajAryan") 
                name.set("Aman Raj Aryan")
                url.set("https://github.com/AmanRajAryan")
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