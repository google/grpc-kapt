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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.google.api.grpc.kapt.GrpcClient
import com.google.api.grpc.kapt.GrpcMarshaller
import com.google.api.grpc.kapt.GrpcServer
import io.grpc.MethodDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

private const val PORT = 8080

/**
 * Run a gRPC client & server defined by the [ComplexService] interface.
 *
 * This example is a more complex example that showcases gRPC
 * streaming methods and using a custom data marshaller.
 *
 * For a simple example, see the example directory in the root of this project.
 */
fun main() = runBlocking {
    // create the server
    ComplexServer().asGrpcServer(PORT) {
        println("Server started on port: $PORT\n")

        // create a client with a new channel and call the server
        ComplexServiceClient.forAddress("localhost", PORT, channelOptions = {
            usePlaintext()
        }).use { client ->
            // unary call
            println(client.ask(Question("what's this?")))
            println()

            // server streaming
            for (answer in client.lecture(Question("my favorite topic"))) {
                println(answer)
            }
            println()

            // client streaming
            println(client.listen(produce {
                repeat(10) { i ->
                    send(Question("I" + " still".repeat(i) + " have a question [#${i + 1}]"))
                }
            }))
            println()

            // bidirectional streaming
            val answers = client.debate(produce {
                repeat(10) { i -> send(Question("[#${i + 1}]")) }
            })
            for (answer in answers) {
                println(answer)
            }
            println()
        }
    }
    println("Server terminated.")
}

// define the data types
data class Question(val query: String)

data class Answer(val result: String)

// generate a gRPC client
@GrpcClient
interface ComplexService {
    suspend fun ask(question: Question): Answer
    suspend fun lecture(topic: Question): ReceiveChannel<Answer>
    suspend fun listen(questions: ReceiveChannel<Question>): Answer
    suspend fun debate(questions: ReceiveChannel<Question>): ReceiveChannel<Answer>
}

// generate a gRPC service
@GrpcServer
class ComplexServer : ComplexService {

    override suspend fun ask(question: Question) = Answer(result = "you said: '${question.query}'")

    override suspend fun lecture(topic: Question) = CoroutineScope(coroutineContext).produce {
        send(Answer("let's talk about '${topic.query}'"))
        repeat(10) { i ->
            send(Answer(Array(i + 1) { "more" }.joinToString(" and ") + "[${i + 1}]"))
        }
    }

    override suspend fun listen(questions: ReceiveChannel<Question>): Answer {
        for (question in questions) {
            println("You asked: '${question.query}'")
        }
        return Answer("Great questions everyone!")
    }

    override suspend fun debate(questions: ReceiveChannel<Question>) = CoroutineScope(coroutineContext).produce {
        for (question in questions) {
            send(Answer("${question.query}? Sorry, I don't know..."))
        }
    }
}

@GrpcMarshaller
object MyMarshallerProvider {
    private val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule())

    fun <T> of(type: Class<T>): MethodDescriptor.Marshaller<T> {
        return object : MethodDescriptor.Marshaller<T> {
            override fun stream(value: T): InputStream = ByteArrayInputStream(mapper.writeValueAsBytes(value))
            override fun parse(stream: InputStream): T = stream.bufferedReader().use { mapper.readValue(it, type) }
        }
    }
}
