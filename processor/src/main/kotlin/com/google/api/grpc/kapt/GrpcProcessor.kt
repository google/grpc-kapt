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

import com.google.api.grpc.kapt.generator.ClientGenerator
import com.google.api.grpc.kapt.generator.ServerGenerator
import com.google.api.grpc.kapt.generator.error
import java.io.File
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedAnnotationTypes
import javax.annotation.processing.SupportedOptions
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.ElementKind
import javax.lang.model.element.TypeElement

private const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"

/**
 * Annotation processor for generating gRPC services & clients.
 */
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedOptions(KAPT_KOTLIN_GENERATED_OPTION_NAME)
@SupportedAnnotationTypes(
    "com.google.api.grpc.kapt.GrpcClient",
    "com.google.api.grpc.kapt.GrpcServer",
    "com.google.api.grpc.kapt.GrpcMethod"
)
class GrpcProcessor : AbstractProcessor() {

    private val clientGenerator by lazy { ClientGenerator(processingEnv) }
    private val serverGenerator by lazy { ServerGenerator(processingEnv) }

    override fun process(annotations: MutableSet<out TypeElement>?, roundEnv: RoundEnvironment): Boolean {
        // sanity checks
        if (roundEnv.getElementsAnnotatedWith(GrpcClient::class.java)
                .any { it.kind != ElementKind.INTERFACE }
        ) {
            processingEnv.error("@GrpcClient can only be applied to interfaces.")
            return false
        }
        if (roundEnv.getElementsAnnotatedWith(GrpcServer::class.java)
                .any { it.kind != ElementKind.CLASS }
        ) {
            processingEnv.error("@GrpcServer can only be applied to classes.")
            return false
        }

        // filter input
        val clientElements = roundEnv
            .getElementsAnnotatedWith(GrpcClient::class.java)
            .filter { it.kind == ElementKind.INTERFACE }
        val serverElements = roundEnv
            .getElementsAnnotatedWith(GrpcServer::class.java)
            .filter { it.kind == ElementKind.CLASS }
        if (clientElements.isEmpty() && serverElements.isEmpty()) {
            return false
        }

        // get output directory
        val outputPath = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME] ?: run {
            processingEnv.error("Can't find the target directory for generated Kotlin files.")
            return false
        }
        val outputDirectory = File(outputPath).also { it.mkdirs() }

        // generate code
        for (el in clientElements) {
            val client = clientGenerator.generate(el)
            client.writeTo(outputDirectory)
        }
        for (el in serverElements) {
            val server = serverGenerator.generate(el)
            server.writeTo(outputDirectory)
        }

        // all done!
        return true
    }
}
