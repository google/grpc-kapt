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
import com.google.api.grpc.kapt.GrpcServer
import com.google.api.grpc.kapt.MarshallerProvider
import io.grpc.BindableService
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerCallHandler
import io.grpc.ServerServiceDefinition
import io.grpc.ServiceDescriptor
import io.grpc.stub.ClientCalls
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val PORT = 8080

/**
 * Run the server and call the [SimpleService.ask] method.
 *
 * This example is a more complex example that showcases gRPC
 * streaming methods and using a custom data marshaller.
 */
fun main() = runBlocking {
    // create the server
    val server = ComplexServiceServer().asGrpcServer(PORT) {
        // create a client with a new channel and call the server
        ManagedChannelBuilder
            .forAddress("localhost", PORT)
            .usePlaintext()
            .asComplexServiceClient().use {
                // unary call
                println(it.ask(Question("what's this?")))

                // server streaming
                for (answer in it.listen(Question("talk about this"))) {
                    println(answer)
                }

                println("FOO")
                // client streaming
                // TODO

                // bidirectional streaming
                // TODO
            }
    }
}

// define the data types
data class Question(val query: String)

data class Answer(val result: String)

// generate a gRPC client
@GrpcClient("Ask", marshaller = MyMarshallerProvider::class)
interface ComplexService {
    suspend fun ask(question: Question): Answer
    suspend fun listen(topic: Question): ReceiveChannel<Answer>
    //suspend fun lecture(answers: SendChannel<Question>): Answer
    //suspend fun debate(questions: SendChannel<Question>): ReceiveChannel<Answer>
}

// generate a gRPC service
@GrpcServer("Ask", marshaller = MyMarshallerProvider::class)
//@ExperimentalCoroutinesApi
class ComplexServiceServer : ComplexService {

    override suspend fun ask(question: Question) = Answer(result = "you said: '${question.query}'")
    override suspend fun listen(topic: Question) = GlobalScope.produce {
        "let's think about: '${topic.query}'".split(" ").forEach { send(Answer(it)) }
    }
}

// example of using a data marshaller
object MyMarshallerProvider : MarshallerProvider {
    private val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule())

    override fun <T> of(type: Class<T>): MethodDescriptor.Marshaller<T> {
        return object : MethodDescriptor.Marshaller<T> {
            override fun stream(value: T): InputStream = ByteArrayInputStream(mapper.writeValueAsBytes(value))
            override fun parse(stream: InputStream): T = stream.bufferedReader().use { mapper.readValue(it, type) }
        }
    }
}
