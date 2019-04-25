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

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.language.v1.AnalyzeEntitiesRequest
import com.google.cloud.language.v1.Document
import com.google.cloud.language.v1.Entity
import io.grpc.CallOptions
import io.grpc.auth.MoreCallCredentials
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ExampleTest {

    @Test
    fun `can call the language api`() = withClient { client ->
        val response = client.analyzeEntities(with(AnalyzeEntitiesRequest.newBuilder()) {
            document = with(Document.newBuilder()) {
                content = "Hi there Joe!"
                type = Document.Type.PLAIN_TEXT
                build()
            }
            build()
        })

        val people = response.entitiesList.filter { it.type == Entity.Type.PERSON }

        assertEquals(1, people.size)
        assertEquals("Joe", people.first().name)
    }
}

private fun <T> withClient(block: suspend (LanguageService) -> T) = runBlocking {
    val options = CallOptions.DEFAULT
        .withCallCredentials(
            MoreCallCredentials.from(
                GoogleCredentials.getApplicationDefault()
            )
        )

    LanguageService.forAddress("language.googleapis.com", 443, callOptions = options).use {
        block(it)
    }
}
