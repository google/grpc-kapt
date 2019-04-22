/*
 *
 *  * Copyright 2019 Google LLC
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     https://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package com.google.api.example

import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ExampleTest {

    @Test
    fun `can say hello`() = withClient { client ->
        val response = client.ask(Question("Hello world!"))
        assertEquals(Answer("you said: 'Hello world!'"), response)
    }
}

private fun <T> withClient(port: Int = 8181, block: suspend (AskClientImpl) -> T) = runBlocking {
    SimpleServiceServer().asGrpcServer(port) {
        ManagedChannelBuilder
            .forAddress("localhost", port)
            .usePlaintext()
            .asSimpleServiceClient().use {
                block(it)
            }
    }
}
