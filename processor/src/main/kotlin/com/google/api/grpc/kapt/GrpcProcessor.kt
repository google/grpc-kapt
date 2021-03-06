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
import com.google.api.grpc.kapt.generator.CodeGenerationException
import com.google.api.grpc.kapt.generator.Generator
import com.google.api.grpc.kapt.generator.proto.KAPT_PROTO_DESCRIPTOR_OPTION_NAME
import com.google.api.grpc.kapt.generator.ServerGenerator
import com.google.api.grpc.kapt.generator.error
import com.squareup.kotlinpoet.asTypeName
import java.io.File
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedAnnotationTypes
import javax.annotation.processing.SupportedOptions
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.TypeElement

private const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"

/**
 * Annotation processor for generating gRPC services & clients.
 */
@SupportedSourceVersion(SourceVersion.RELEASE_8)
@SupportedOptions(KAPT_KOTLIN_GENERATED_OPTION_NAME,
    KAPT_PROTO_DESCRIPTOR_OPTION_NAME
)
@SupportedAnnotationTypes(
    "com.google.api.grpc.kapt.GrpcClient",
    "com.google.api.grpc.kapt.GrpcServer",
    "com.google.api.grpc.kapt.GrpcMethod",
    "com.google.api.grpc.kapt.GrpcMarshaller"
)
class GrpcProcessor : AbstractProcessor() {

    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val annotatedMarshallers = roundEnv.getElementsAnnotatedWith(GrpcMarshaller::class.java).toSet<Element>()
        val annotatedClients = roundEnv.getElementsAnnotatedWith(GrpcClient::class.java).toSet<Element>()
        val annotatedServers = roundEnv.getElementsAnnotatedWith(GrpcServer::class.java).toSet<Element>()

        // sanity checks
        if (annotatedClients.any { it.kind != ElementKind.INTERFACE }) {
            processingEnv.error("@GrpcClient can only be applied to interfaces.")
            return false
        }
        if (annotatedServers.any { it.kind != ElementKind.CLASS }) {
            processingEnv.error("@GrpcServer can only be applied to classes.")
            return false
        }
        if (annotatedMarshallers.any { it.kind != ElementKind.CLASS }) {
            processingEnv.error("@GrpcMarshaller can only be applied to classes/objects and.")
            return false
        }
        if (annotatedMarshallers.size > 1) {
            processingEnv.error("@GrpcMarshaller may be applied at most once (${annotatedMarshallers.size} found).")
            return false
        }

        // filter input
        if (annotatedClients.isEmpty() && annotatedServers.isEmpty()) {
            return false
        }

        // set the default marshaller
        annotatedMarshallers.firstOrNull()?.apply {
            Generator.DEFAULT_MARSHALLER = this.asType().asTypeName()
        }

        // get output directory
        val outputPath = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME] ?: run {
            processingEnv.error("Can't find the target directory for generated Kotlin files.")
            return false
        }
        val outputDirectory = File(outputPath).also { it.mkdirs() }

        // create generators
        val clientGenerator = ClientGenerator(processingEnv)
        val serverGenerator = ServerGenerator(processingEnv, annotatedClients)

        // generate code
        try {
            for (el in annotatedClients) {
                for (file in clientGenerator.generate(el)) {
                    file.writeTo(outputDirectory)
                }
            }
            for (el in annotatedServers) {
                for (file in serverGenerator.generate(el)) {
                    file.writeTo(outputDirectory)
                }
            }
        } catch (ex: CodeGenerationException) {
            processingEnv.error("Code generator error: $ex")
        } catch (t: Throwable) {
            processingEnv.error("Unexpected error: $t")
            throw t
        }

        // all done!
        return true
    }
}
