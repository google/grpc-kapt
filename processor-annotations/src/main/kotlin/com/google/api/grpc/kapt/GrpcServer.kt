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
 * Marks a gRPC service implementation.
 *
 * The fully qualified name of the service will be: [packageName].[name]
 *
 * A [marshaller] can be provided to override the default [GrpcMarshaller] and
 * must be a singleton object that implements [MarshallerProvider].
 *
 * Example:
 * ```
 * import com.google.api.grpc.kapt.GrpcServer
 * import kotlinx.coroutines.channels.ReceiveChannel
 *
 * @GrpcServer("Ask")
 * class MyServiceServer : MyService, CoroutineScope {
 *   // implement the interface...
 * }
 *
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
 *     // bidirectional streaming
 *     suspend fun debate(questions: ReceiveChannel<Question>): ReceiveChannel<Answer>
 * }
 *
 * // Create a server
 * val server = MyServiceServer().asGrpcServer(8080)
 * server.start()
 * ```
*/
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS)
annotation class GrpcServer(
    val name: String = "",
    val packageName: String = "",
    val marshaller: KClass<*> = Unit::class,
    val suffix: String = "Impl"
)
