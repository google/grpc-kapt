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
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.language.v1.AnalyzeEntitiesRequest
import com.google.cloud.language.v1.AnalyzeEntitiesResponse
import com.google.cloud.language.v1.AnalyzeEntitySentimentRequest
import com.google.cloud.language.v1.AnalyzeEntitySentimentResponse
import com.google.cloud.language.v1.AnalyzeSentimentRequest
import com.google.cloud.language.v1.AnalyzeSentimentResponse
import com.google.cloud.language.v1.AnalyzeSyntaxRequest
import com.google.cloud.language.v1.AnalyzeSyntaxResponse
import com.google.cloud.language.v1.AnnotateTextRequest
import com.google.cloud.language.v1.AnnotateTextResponse
import com.google.cloud.language.v1.ClassifyTextRequest
import com.google.cloud.language.v1.ClassifyTextResponse
import com.google.cloud.language.v1.Document
import com.google.protobuf.Message
import io.grpc.CallOptions
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.auth.MoreCallCredentials
import io.grpc.protobuf.ProtoUtils
import kotlinx.coroutines.runBlocking

/**
 * You must set the `GOOGLE_APPLICATION_CREDENTIALS` environment variable to use a
 * valid credentials file and have permission to use the [Language API](https://cloud.google.com/natural-language).
 */
fun main(): Unit = runBlocking {
    // configure the client to use the auth credentials
    val options = CallOptions.DEFAULT
        .withCallCredentials(
            MoreCallCredentials.from(
                GoogleCredentials.getApplicationDefault()
            )
        )

    ManagedChannelBuilder
        .forAddress("language.googleapis.com", 443)
        .asGoogleLanguageServiceClient(options).use {
            val response = it.analyzeEntities(with(AnalyzeEntitiesRequest.newBuilder()) {
                document = with(Document.newBuilder()) {
                    content = "Hi there Joe!"
                    type = Document.Type.PLAIN_TEXT
                    build()
                }
                build()
            })
            println(response)
        }
}

// generate a gRPC client
@GrpcClient(definedBy = "google.cloud.language.v1.LanguageService")
interface GoogleLanguageService

@GrpcMarshaller
object MyMarshallerProvider {
    @Suppress("UNCHECKED_CAST")
    fun <T : Message> of(type: Class<T>): MethodDescriptor.Marshaller<T> =
        ProtoUtils.marshaller(type.getMethod("getDefaultInstance").invoke(null) as T)
}
