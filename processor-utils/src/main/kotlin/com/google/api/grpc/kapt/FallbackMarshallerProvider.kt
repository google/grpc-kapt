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

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import io.grpc.MethodDescriptor.Marshaller
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64

/**
 * A JSON marshaller to use as a default when a custom marshaller is not provided.
 *
 * This is provided for convenience and it is not optimized.
 */
object FallbackMarshallerProvider : MarshallerProvider {
    private val gson: Gson = with(GsonBuilder()) {
        registerTypeAdapter(ByteArray::class.java, object : TypeAdapter<ByteArray>() {
            override fun write(out: JsonWriter, value: ByteArray) {
                out.value(Base64.getEncoder().encodeToString(value))
            }

            override fun read(input: JsonReader): ByteArray = Base64.getDecoder().decode(input.nextString())
        })
        create()
    }

    override fun <T> of(type: Class<T>): Marshaller<T> {
        return object : Marshaller<T> {
            override fun stream(value: T): InputStream = ByteArrayInputStream(gson.toJson(value, type).toByteArray())
            override fun parse(stream: InputStream): T = stream.bufferedReader().use { gson.fromJson(it, type) }
        }
    }
}
