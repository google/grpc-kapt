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

package com.google.api.grpc.kapt

import kompile.testing.kotlinc

typealias KotlinSource = Pair<String, String>

fun kaptTest(input: List<KotlinSource>, output: List<KotlinSource>): Unit {
    val compiler = kotlinc()
        .withProcessors(GrpcProcessor())

    for ((name, content) in input) {
        compiler.addKotlin(name, content)
    }

    val result = compiler.compile()
        .succeededWithoutWarnings()

    for((name, content) in output) {
        result.generatedFile(name).hasSourceEquivalentTo(content)
    }
}

fun kaptTestFail(input: List<KotlinSource>, message: String): Unit {
    val compiler = kotlinc()
        .withProcessors(GrpcProcessor())

    for ((name, content) in input) {
        compiler.addKotlin(name, content)
    }

    compiler.compile()
        .failed()
        .withErrorContaining(message)
}
