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
import com.google.api.grpc.kapt.GrpcMarshaller
import com.google.api.grpc.kapt.GrpcServer
import com.google.example.kapt.Answer
import com.google.example.kapt.Question
import com.google.protobuf.Message
import io.grpc.MethodDescriptor
import io.grpc.protobuf.ProtoUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext

private const val PORT = 8080

/**
 * Run a gRPC client & server defined by the [MyProtoService], which is created from
 * `src/main/proto/google/example/kapt/ProtoService.proto`.
 *
 * This example is a more complex example that showcases gRPC
 * streaming methods and using a custom data marshaller.
 *
 * For a simple example, see the example directory in the root of this project.
 */
fun main(): Unit = runBlocking {
    ProtoServer().asGrpcServer(PORT) {
        ProtoService.forAddress("localhost", PORT, channelOptions = { usePlaintext() }).use { client ->
            // unary call
            println(client.ask(question { query = "what's this?" }))

            // server streaming
            for (answer in client.lecture(question { query = "my favorite topic" })) {
                println(answer)
            }

            // client streaming
            println(client.listen(produce {
                repeat(10) { i ->
                    send(question { query = "I" + " still".repeat(i) + " have a question [#${i + 1}]" })
                }
            }))

            // bidirectional streaming
            val answers = client.debate(produce {
                repeat(10) { i -> send(question { query = "Question #${i + 1}" }) }
            })
            for (answer in answers) {
                println(answer)
            }
        }
    }
}

// generate a gRPC client
@GrpcClient(definedBy = "google.example.kapt.ProtoService")
interface MyProtoService

@GrpcServer
class ProtoServer : ProtoService, MyProtoService {
    override suspend fun ask(question: Question) = answer { result = "you said: '${question.query}'" }

    override suspend fun lecture(topic: Question) = CoroutineScope(coroutineContext).produce {
        send(answer { result = "let's talk about '${topic.query}'" })
        repeat(10) { i ->
            send(answer { result = Array(i + 1) { "more" }.joinToString(" and ") + "[${i + 1}]" })
        }
    }

    override suspend fun listen(questions: ReceiveChannel<Question>): Answer {
        for (question in questions) {
            println("You asked: '${question.query}'")
        }
        return answer { result = "Great questions everyone!" }
    }

    override suspend fun debate(questions: ReceiveChannel<Question>) = CoroutineScope(coroutineContext).produce {
        for (question in questions) {
            send(answer { result = "${question.query}? Sorry, I don't know..." })
        }
    }
}

@GrpcMarshaller
object ProtoMarshallerProvider {
    @Suppress("UNCHECKED_CAST")
    fun <T : Message> of(type: Class<T>): MethodDescriptor.Marshaller<T> =
        ProtoUtils.marshaller(type.getMethod("getDefaultInstance").invoke(null) as T)
}

// helpers for the proto builders
fun answer(builder: Answer.Builder.() -> Unit): Answer = Answer.newBuilder().apply(builder).build()

fun question(builder: Question.Builder.() -> Unit): Question = Question.newBuilder().apply(builder).build()
