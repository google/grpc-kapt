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

package com.google.api.grpc.kapt

import kotlin.test.Test

class GrpcClientTest {

    @Test
    fun `can not use @GrpcClient on a class element`() = kaptTestFail(
        input = listOf(
            "foo.kt" to "@com.google.api.grpc.kapt.GrpcClient class TestClass"
        ),
        message = "@GrpcClient can only be applied to interfaces."
    )

    @Test
    fun `can use @GrpcClient on a packageless interface`() = kaptTest(
        input = listOf(
            "foo.kt" to "@com.google.api.grpc.kapt.GrpcClient interface TestThing"
        )
    )

    @Test
    fun `can use @GrpcClient on an interface`() = kaptTest(
        input = listOf(
            "/baz/foo/bar/foo.kt" to
                """
                |package baz.foo.bar
                |@com.google.api.grpc.kapt.GrpcClient interface TestThing
                |""".trimMargin()
        )
    )
}
