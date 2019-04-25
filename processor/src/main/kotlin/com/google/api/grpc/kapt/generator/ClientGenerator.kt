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

package com.google.api.grpc.kapt.generator

import com.google.api.grpc.kapt.GrpcClient
import com.google.api.grpc.kapt.generator.proto.ProtoInterfaceGenerator
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.stub.ClientCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.TimeUnit
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element

/**
 * Generates a gRPC client from an interface annotated with [GrpcClient].
 */
internal class ClientGenerator(
    override val environment: ProcessingEnvironment
) : Generator<List<FileSpec>> {
    private val protoGenerator by lazy {
        ProtoInterfaceGenerator(environment)
    }

    override fun generate(element: Element): List<FileSpec> {
        val annotation = element.getAnnotation(GrpcClient::class.java)
            ?: throw CodeGenerationException("Unsupported element: $element")

        // get metadata required for generation
        val kmetadata = element.asKotlinMetadata()
        val typeInfo = annotation.extractTypeInfo(element, environment, annotation.suffix)

        val interfaceName = element.asGeneratedInterfaceName()

        // if this element is defined by another model (i.e. proto IDL)
        // then generate the interface and append to the client method list
        val generatedInterface = if (annotation.definedBy.isNotBlank()) {
            protoGenerator.generate(element)
        } else {
            GeneratedInterface(
                TypeSpec.interfaceBuilder("${interfaceName}Client")
                    .addKdoc(
                        """
                        |A gRPC client for [%L] using the [channel] governed by the [callOptions].
                        """.trimMargin(),
                        interfaceName
                    )
                    .addSuperinterface(element.asGeneratedInterfaceType())
                    .addSuperinterface(AutoCloseable::class)
                    .addProperties(COMMON_INTERFACE_PROPERTIES)
                    .build(),
                mapOf()
            )
        }

        val interfaceType = ClassName(typeInfo.type.packageName, generatedInterface.type.name!!)
        val marshallerType = annotation.asMarshallerType()

        // generate the client
        val clientBuilder = FileSpec.builder(typeInfo.type.packageName, typeInfo.type.simpleName)

        // add the constructor extension methods
        clientBuilder.addFunction(
            FunSpec.builder("as${interfaceName}Client")
                .receiver(ManagedChannelBuilder::class.asClassName().parameterizedBy(TypeVariableName("*")))
                .addParameter(
                    ParameterSpec.builder("callOptions", CallOptions::class)
                        .defaultValue("%T.DEFAULT", CallOptions::class.asTypeName())
                        .build()
                )
                .addParameter(
                    ParameterSpec.builder("coroutineScope", CoroutineScope::class.asClassName())
                        .defaultValue("%T", GlobalScope::class.asClassName())
                        .build()
                )
                .returns(interfaceType)
                .addStatement("return %T(this.build(), callOptions, coroutineScope)", typeInfo.type)
                .addKdoc(
                    """
                        |Create a new client with a new [%T] and [callOptions].
                        |
                        |Streaming methods are launched in the [coroutineScope], which is the [%T] by default.
                        """.trimMargin(),
                    ManagedChannel::class,
                    GlobalScope::class.asTypeName()
                )
                .build()
        ).addFunction(
            FunSpec.builder("as${interfaceName}Client")
                .receiver(ManagedChannel::class)
                .addParameter(
                    ParameterSpec.builder("callOptions", CallOptions::class)
                        .defaultValue("%T.DEFAULT", CallOptions::class.asTypeName())
                        .build()
                )
                .addParameter(
                    ParameterSpec.builder("coroutineScope", CoroutineScope::class.asClassName())
                        .defaultValue("%T", GlobalScope::class.asClassName())
                        .build()
                )
                .returns(interfaceType)
                .addStatement("return %T(this, callOptions, coroutineScope)", typeInfo.type)
                .addKdoc(
                    """
                        |Create a new client using the given [%T] and [callOptions].
                        |
                        |Streaming methods are launched in the [coroutineScope], which is the [%T] by default.
                        """.trimMargin(),
                    ManagedChannel::class,
                    GlobalScope::class.asTypeName()
                )
                .build()
        )

        // add the types
        clientBuilder.addType(generatedInterface.type)
        clientBuilder.addType(
            with(TypeSpec.classBuilder(typeInfo.type)) {
                addModifiers(KModifier.PRIVATE)
                addSuperinterface(interfaceType)
                primaryConstructor(
                    FunSpec.constructorBuilder()
                        .addParameter("channel", ManagedChannel::class)
                        .addParameter(
                            ParameterSpec.builder("callOptions", CallOptions::class)
                                .defaultValue("%T.DEFAULT", CallOptions::class.asTypeName())
                                .build()
                        )
                        .addParameter(
                            ParameterSpec.builder("coroutineScope", CoroutineScope::class.asClassName())
                                .defaultValue("%T", GlobalScope::class.asClassName())
                                .build()
                        )
                        .build()
                )
                addProperty(
                    PropertySpec.builder("channel", ManagedChannel::class)
                        .addModifiers(KModifier.OVERRIDE)
                        .initializer("channel")
                        .build()
                )
                addProperty(
                    PropertySpec.builder("callOptions", CallOptions::class)
                        .addModifiers(KModifier.OVERRIDE)
                        .initializer("callOptions")
                        .build()
                )
                addProperty(
                    PropertySpec.builder("coroutineScope", CoroutineScope::class.asClassName())
                        .addModifiers(KModifier.PRIVATE)
                        .initializer("coroutineScope")
                        .build()
                )
                addProperties(
                    element.filterRpcMethods().map {
                        kmetadata.describeElement(it)
                            .asDescriptorProperty(typeInfo, marshallerType)
                    }
                )
                addProperties(
                    generatedInterface.asMethods().map {
                        it.asDescriptorProperty(typeInfo, marshallerType)
                    }
                )
                addFunctions(
                    element.filterRpcMethods().map { kmetadata.describeElement(it).asRpcMethod() }
                )
                addFunctions(
                    generatedInterface.asMethods().map { it.asRpcMethod() }
                )
                addFunction(
                    FunSpec.builder("close")
                        .addModifiers(KModifier.OVERRIDE)
                        .addStatement(
                            "channel.shutdown().awaitTermination(5, %T.SECONDS)",
                            TimeUnit::class.asTypeName()
                        )
                        .build()
                )
            }.build()
        )

        // add additional imports
        clientBuilder.addImport("kotlin.coroutines", "resume", "resumeWithException")
            .addImport("kotlinx.coroutines", "launch", "runBlocking", "suspendCancellableCoroutine")
            .addImport("kotlinx.coroutines.guava", "await")
            .build()

        return listOf(clientBuilder.build())
    }

    private fun KotlinMethodInfo.asRpcMethod(): FunSpec {
        val requestVar = parameters.first().name
        val requestType = parameters.first().type
        val callVar = "call".unless(requestVar)

        // TODO: support futures or something other than suspending functions?
        if (!isSuspend) {
            throw CodeGenerationException("Client methods must have the 'suspend' keyword.")
        }

        // build method
        val builder = FunSpec.builder(name)
        builder.addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .addParameter(requestVar, requestType)
            .returns(returns)
            .addStatement(
                "val %L = channel.newCall(this.%L, this.callOptions)",
                callVar, rpc.name.decapitalize()
            )

        // implement method based on rpc method type
        if (rpc.type == MethodDescriptor.MethodType.SERVER_STREAMING) {
            val dataVar = "data".unless(callVar, requestVar)
            val channelVar = "channel".unless(callVar, requestVar, dataVar)
            builder.addCode(
                """
                |val %L: %T = Channel()
                |this.coroutineScope.launch {
                |    suspendCancellableCoroutine { cont: %T ->
                |        %T.asyncServerStreamingCall(call, %L, object : %T {
                |            override fun onNext(value: %T) {
                |                runBlocking { %L.send(value) }
                |            }
                |            override fun onError(t: Throwable) {
                |                %L.close(t)
                |                cont.resumeWithException(t)
                |            }
                |            override fun onCompleted() {
                |                %L.close()
                |                cont.resume(Unit)
                |            }
                |        })
                |    }
                |}
                |return %L
                |""".trimMargin(),
                channelVar,
                Channel::class.asClassName().parameterizedBy(rpc.outputType),
                CancellableContinuation::class.asClassName().parameterizedBy(Unit::class.asTypeName()),
                ClientCalls::class.asTypeName(), requestVar,
                StreamObserver::class.asClassName().parameterizedBy(rpc.outputType),
                rpc.outputType,
                channelVar,
                channelVar,
                channelVar,
                channelVar
            )
        } else if (rpc.type == MethodDescriptor.MethodType.CLIENT_STREAMING) {
            val dataVar = "data".unless(callVar, requestVar)
            val requestStreamVar = "requestStream".unless(callVar, requestVar, dataVar)
            builder.addCode(
                """
                |return suspendCancellableCoroutine { cont: %T ->
                |    val %L = %T.asyncClientStreamingCall(call, object : %T {
                |        override fun onNext(value: %T) {
                |            cont.resume(value)
                |        }
                |        override fun onError(t: Throwable) {
                |            cont.resumeWithException(t)
                |        }
                |        override fun onCompleted() {}
                |    })
                |    this.coroutineScope.launch {
                |        try {
                |            for (request in %L) {
                |                %L.onNext(request)
                |            }
                |            %L.onCompleted()
                |        } catch (_ : %T) {
                |            %L.onCompleted()
                |        } catch (t: Throwable) {
                |            %L.onError(t)
                |        }
                |    }
                |}
                |""".trimMargin(),
                CancellableContinuation::class.asClassName().parameterizedBy(rpc.outputType),
                requestStreamVar, ClientCalls::class.asTypeName(),
                StreamObserver::class.asClassName().parameterizedBy(rpc.outputType),
                rpc.outputType,
                requestVar,
                requestStreamVar,
                requestStreamVar,
                CancellationException::class.asClassName(),
                requestStreamVar,
                requestStreamVar
            )
        } else if (rpc.type == MethodDescriptor.MethodType.BIDI_STREAMING) {
            val dataVar = "data".unless(callVar, requestVar)
            val channelVar = "channel".unless(callVar, requestVar, dataVar)
            val requestStreamVar = "requestStream".unless(callVar, requestVar, dataVar, channelVar)
            builder.addCode(
                """
                |val %L: %T = Channel()
                |this.coroutineScope.launch {
                |    suspendCancellableCoroutine { cont: %T ->
                |        val %L = %T.asyncBidiStreamingCall(call, object : %T {
                |            override fun onNext(value: %T) {
                |                runBlocking { %L.send(value) }
                |            }
                |            override fun onError(t: Throwable) {
                |                %L.close(t)
                |                cont.resumeWithException(t)
                |            }
                |            override fun onCompleted() {
                |                %L.close()
                |                cont.resume(Unit)
                |            }
                |        })
                |        coroutineScope.launch {
                |            try {
                |                for (request in %L) {
                |                    %L.onNext(request)
                |                }
                |                %L.onCompleted()
                |            } catch (_ : %T) {
                |                %L.onCompleted()
                |            } catch (t: Throwable) {
                |                %L.onError(t)
                |            }
                |        }
                |    }
                |}
                |return %L
                |""".trimMargin(),
                channelVar,
                Channel::class.asClassName().parameterizedBy(rpc.outputType),
                CancellableContinuation::class.asClassName().parameterizedBy(Unit::class.asTypeName()),
                requestStreamVar, ClientCalls::class.asTypeName(),
                StreamObserver::class.asClassName().parameterizedBy(rpc.outputType),
                rpc.outputType,
                channelVar,
                channelVar,
                channelVar,

                requestVar,
                requestStreamVar,
                requestStreamVar,
                CancellationException::class.asClassName(),
                requestStreamVar,
                requestStreamVar,

                channelVar
            )
        } else { // MethodDescriptor.MethodType.UNARY
            builder.addStatement(
                "return %T.futureUnaryCall(%L, %L).await()",
                ClientCalls::class.asTypeName(), callVar, requestVar
            )
        }

        return builder.build()
    }

    private fun KotlinMethodInfo.asDescriptorProperty(
        annotationInfo: AnnotatedTypeInfo,
        marshallerType: TypeName
    ): PropertySpec {
        val type = MethodDescriptor::class.asClassName()
            .parameterizedBy(rpc.inputType, rpc.outputType)

        return PropertySpec.builder(rpc.name.decapitalize(), type)
            .addModifiers(KModifier.PRIVATE)
            .delegate(
                """
                |lazy·{
                |    %T.newBuilder(
                |        %T.of(%T::class.java),
                |        %T.of(%T::class.java)
                |    )
                |    .setFullMethodName(
                |        MethodDescriptor.generateFullMethodName(%S, %S)
                |     )
                |    .setType(MethodDescriptor.MethodType.%L)
                |    .build()
                |}
                |""".trimMargin(),
                MethodDescriptor::class.asClassName(),
                marshallerType, rpc.inputType,
                marshallerType, rpc.outputType,
                rpc.packageName ?: annotationInfo.fullName, rpc.name,
                rpc.type.name
            )
            .build()
    }

    companion object {
        /** All properties on the client interface type. */
        val COMMON_INTERFACE_PROPERTIES = listOf(
            PropertySpec.builder("channel", ManagedChannel::class)
                .addKdoc("The [channel] that was used to create the client.")
                .build(),
            PropertySpec.builder("callOptions", CallOptions::class)
                .addKdoc("The [callOptions] that were used to create the client.")
                .build()
        )
    }
}
