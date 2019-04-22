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

import kotlin.reflect.KClass

/**
 * Marks a gRPC client interface. The interface may include any number of methods, but they
 * must all follow one of the four patterns shown in the example below (corresponding to the
 * four gRPC method [io.grpc.MethodDescriptor.MethodType]s).
 *
 * The fully qualified name of the service used will be: [packageName].[name]
 *
 * A [marshaller] can be provided to override the default [GrpcMarshaller] and
 * must be a singleton object that implements [MarshallerProvider].
 *
 * Example:
 * ```
 * import com.google.api.grpc.kapt.GrpcClient
 * import io.grpc.ManagedChannelBuilder
 * import kotlinx.coroutines.channels.ReceiveChannel

 * @GrpcClient("Ask")
 * interface MyService {
 *     // a unary method (most common type)
 *     suspend fun ask(question: Question): Answer
 *
 *     // server streaming
 *     suspend fun lecture(topic: Question): ReceiveChannel<Answer>
 *
 *     // client streaming
 *     suspend fun listen(questions: ReceiveChannel<Question>): Answer
 *
 *     // bidirectional streming
 *     suspend fun debate(questions: ReceiveChannel<Question>): ReceiveChannel<Answer>
 * }
 *
 * // Use the client
 * ManagedChannelBuilder
 *     .forAddress("localhost", 8080)
 *     .usePlaintext()
 *     .asMyServiceClient().use { client ->
 *        // use the client to call the server
 * }
 * ```
 */
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class GrpcClient(
    val name: String,
    val packageName: String = "",
    val marshaller: KClass<*> = Unit::class
)
