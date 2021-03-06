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

import com.google.protobuf.gradle.protobuf
import com.google.protobuf.gradle.protoc
import com.google.protobuf.gradle.generateProtoTasks
import com.google.protobuf.gradle.ofSourceSet

plugins {
    idea
    maven
    application
    kotlin("jvm")
    kotlin("kapt")
    id("com.google.protobuf") version "0.8.8"
}

application {
    mainClassName = "com.google.api.example.ExampleKt"
}

defaultTasks = listOf("run")

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.2.2")

    implementation("io.grpc:grpc-netty-shaded:1.20.0")
    implementation("io.grpc:grpc-stub:1.20.0")
    implementation("io.grpc:grpc-auth:1.20.0")
    implementation("io.grpc:grpc-protobuf:1.20.0")

    implementation("com.google.auth:google-auth-library-oauth2-http:0.15.0")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.12")

    // add the annotation process to generate the gRPC code
    compileOnly(project(":processor-annotations"))
    kapt(project(":processor"))
}

kapt {
    arguments {
        arg("protos", "$buildDir/generated/source/proto/main/descriptor_set.desc")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.7.1"
    }
    generateProtoTasks {
        ofSourceSet("main").forEach {
            it.generateDescriptorSet = true
            it.descriptorSetOptions.includeSourceInfo = true
        }
    }
}
