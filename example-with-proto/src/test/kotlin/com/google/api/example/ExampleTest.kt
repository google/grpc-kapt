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

import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PORT = 8484

@ExperimentalCoroutinesApi
class ExampleTest {

    companion object {
        private var server = ProtoServer().asGrpcServer(PORT).also { it.start() }

        @AfterClass
        @JvmStatic
        fun after() {
            server.shutdown().awaitTermination()
        }
    }

    @Test
    fun `can call a unary method`() = withClient { client ->
        val response = client.ask(question { query = "Hello world!" })
        assertEquals(answer { result = "you said: 'Hello world!'" }, response)
    }

    @Test
    fun `can call a server streaming method`() = withClient { client ->
        val responses = client.lecture(question { query = "what?" }).toList()
        val expected = listOf(answer { result = "let's talk about 'what?'" }) +
            List(10) { i ->
                answer { result = "more" + " and more".repeat(i) + "[${i + 1}]" }
            }
        assertEquals(expected.toSet(), responses.toSet())
    }

    @Test
    fun `can call a client streaming method`() = withClient { client ->
        val response = client.listen(GlobalScope.produce {
            for (item in listOf("one", "two", "three", "four")) {
                send(question { query = item })
            }
        })
        val expected = answer { result = "Great questions everyone!" }
        assertEquals(expected, response)
    }

    @Test
    fun `can call a bidirectional streaming method`() = withClient { client ->
        val responses = client.debate(GlobalScope.produce {
            for (item in listOf("a", "b", "c", "d")) {
                send(question { query = item })
            }
        }).toList()
        val expected = listOf("a", "b", "c", "d").map {
            answer { result = "$it? Sorry, I don't know..." }
        }
        assertEquals(expected, responses)
    }
}

private fun <T> withClient(block: suspend (ProtoService) -> T) = runBlocking {
    ManagedChannelBuilder
        .forAddress("localhost", PORT)
        .usePlaintext()
        .asProtoService().use {
            block(it)
        }
}
