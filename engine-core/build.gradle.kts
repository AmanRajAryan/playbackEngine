import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("com.android.library")
  id("kotlin-android")
  alias(libs.plugins.vanniktech.maven.publish)
}

android {
  namespace = "aman.playbackengine.enginecore"
  compileSdk = 36

  defaultConfig {
    minSdk = 24
    consumerProguardFiles("consumer-rules.pro")
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
  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation("androidx.annotation:annotation:1.7.0")
  implementation(libs.kotlinx.coroutines.android)
  
  // Media3 for Session support
  implementation(libs.androidx.media3.session)
  implementation(libs.androidx.media3.common)
}

mavenPublishing {
    coordinates("io.github.amanrajaryan", "playback-engine-core", "1.0.0")

    pom {
        name.set("playback-engine-core")
        description.set("Core interfaces and orchestration logic for the playback library")
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