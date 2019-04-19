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
    application
    kotlin("jvm")
    kotlin("kapt")
}

application {
    mainClassName = "com.google.api.example.ExampleKt"
}

defaultTasks = listOf("run")

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.1.1")

    implementation("io.grpc:grpc-netty-shaded:1.20.0")
    implementation("io.grpc:grpc-stub:1.20.0")

    // example for marshalling with jackson instead of gson or protobuf
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.7")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.12")

    implementation(project(":processor-annotations"))
    kapt(project(":processor"))
}
