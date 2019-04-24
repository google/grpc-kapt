/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    idea
    maven
    kotlin("jvm") version "1.3.30"
}

dependencies {
    compile(kotlin("stdlib-jdk8"))

    compile(project(":processor"))
    compile(project(":processor-annotations"))

    compile(project(":example"))
    compile(project(":example-with-streams"))
    compile(project(":example-with-google-api"))
}

allprojects {
    group = "com.google.api"
    version = "0.1.0-SNAPSHOT"

    buildscript {
        repositories {
            google()
            mavenCentral()
            jcenter()
        }
    }

    repositories {
        google()
        mavenCentral()
        jcenter()
        maven(url = "https://kotlin.bintray.com/kotlinx/")
    }
}

subprojects {
    val ktlintImplementation by configurations.creating

    dependencies {
        ktlintImplementation("com.github.shyiko:ktlint:0.31.0")
    }

    afterEvaluate {
        tasks {
            val check = getByName("check")

            val ktlint by creating(JavaExec::class) {
                group = "verification"
                description = "Check Kotlin code style."
                main = "com.github.shyiko.ktlint.Main"
                classpath = ktlintImplementation
                args = listOf("src/**/*.kt", "test/**/*.kt")
            }
            check.dependsOn(ktlint)

            val ktlintFormat by creating(JavaExec::class) {
                group = "formatting"
                description = "Fix Kotlin code style deviations."
                main = "com.github.shyiko.ktlint.Main"
                classpath = ktlintImplementation
                args = listOf("-F", "src/**/*.kt", "test/**/*.kt")
            }
        }
    }
}

tasks {
    val runExample by registering {
        dependsOn(getByPath(":example:run"))
    }
    val runExampleWithStreams by registering {
        dependsOn(getByPath(":example-with-streams:run"))
    }
    val runExampleWithGoogle by registering {
        dependsOn(getByPath(":example-with-google-api:run"))
    }
}