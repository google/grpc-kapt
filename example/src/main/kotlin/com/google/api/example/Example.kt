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

package com.google.api.example

import com.google.api.grpc.kapt.GrpcClient
import com.google.api.grpc.kapt.GrpcServer
import kotlinx.coroutines.runBlocking

private const val PORT = 8080

/**
 * Run the server and call the [SimpleService.ask] method.
 *
 * This example uses the default JSON marshaller to serialize the data types.
 *
 * For a more complex example, see the example-with-streams directory in the root of this project.
 */
fun main() = runBlocking {
    // create the server
    SimpleServer().asGrpcServer(PORT) {
        // create a client with a new channel and call the server
        SimpleServiceClient.forAddress("localhost", PORT, channelOptions = {
            usePlaintext()
        }).use {
            val answer = it.ask(Question("what's this?"))
            println(answer)
        }
    }
}

// define the data types
data class Question(val query: String)

data class Answer(val result: String)

// generate a gRPC client
@GrpcClient
interface SimpleService {
    suspend fun ask(question: Question): Answer
}

// generate a gRPC service
@GrpcServer
class SimpleServer : SimpleService {
    override suspend fun ask(question: Question) = Answer(result = "you said: '${question.query}'")
}
