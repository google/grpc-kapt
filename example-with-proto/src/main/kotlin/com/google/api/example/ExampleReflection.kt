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
import com.google.protobuf.DescriptorProtos
import grpc.reflection.v1alpha.ServerReflectionRequest
import io.grpc.ServerBuilder
import io.grpc.protobuf.services.ProtoReflectionService
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.runBlocking

private const val PORT = 8080

/**
 * Example showing how to use this project to start a server with gRPC reflection (requires proto) and
 * how to download the a [DescriptorProtos.FileDescriptorSet] that describes the running service. This
 * descriptor can be used for code gen (via the [GrpcClient.definedBy] attribute).
 */
fun main(): Unit = runBlocking {
    val server = ServerBuilder.forPort(PORT)
        .addService(ProtoServer().asGrpcService())
        .addService(ProtoReflectionService.newInstance())
        .build()
        .start()

    // download the descriptor
    ServerReflection.forAddress("localhost", 8080, channelOptions = { usePlaintext() }).use { client ->
        val respones = client.serverReflectionInfo(produce {
            // ask for a list of all services
            send(serverReflectionRequest { listServices = "" })

            // ask for the details of our example service
            send(serverReflectionRequest {
                fileContainingSymbol = "google.example.kapt.ProtoService"
            })
        })

        // aggregate a file descriptor set for our ProtoService (can be used for codegen)
        val fileDescriptorSet = with(DescriptorProtos.FileDescriptorSet.newBuilder()) {
            for (response in respones) {
                if (response.hasFileDescriptorResponse()) {
                    response.fileDescriptorResponse.fileDescriptorProtoList.map {
                        addFile(DescriptorProtos.FileDescriptorProto.parseFrom(it))
                    }
                } else {
                    println(response)
                }
            }
            build()
        }

        println("Discovered file descriptor set:\n\n$fileDescriptorSet")
    }

    // shutdown the server
    server.shutdown().awaitTermination()
}

// generate a gRPC client for the reflection service
@GrpcClient(definedBy = "grpc.reflection.v1alpha.ServerReflection")
interface ReflectionService

fun serverReflectionRequest(builder: ServerReflectionRequest.Builder.() -> Unit): ServerReflectionRequest =
    ServerReflectionRequest.newBuilder().apply(builder).build()
