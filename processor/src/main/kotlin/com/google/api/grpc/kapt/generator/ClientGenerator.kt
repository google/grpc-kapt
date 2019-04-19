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
import kotlinx.metadata.jvm.KotlinClassMetadata
import java.util.concurrent.TimeUnit
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.type.MirroredTypeException

/**
 * Generates a gRPC client from an interface annotated with [GrpcClient].
 */
internal class ClientGenerator(
    override val environment: ProcessingEnvironment,
    private val suffix: String = "ClientImpl"
) : Generator {
    override fun generate(element: Element): FileSpec {
        val annotation = element.getAnnotation(GrpcClient::class.java)
            ?: throw CodeGenerationException("Unsupported element: $element")

        val interfaceName = element.asGeneratedInterfaceName()
        val interfaceType = element.asGeneratedInterfaceType()

        // get metadata required for generation
        val kmetadata = element.asKotlinMetadata()

        // determine appropriate names to use for the service
        val simpleServiceName = if (annotation.name.isNotBlank()) {
            annotation.name
        } else {
            interfaceName
        }
        val typeName = if (annotation.packageName.isNotBlank()) {
            ClassName(annotation.packageName, simpleServiceName + suffix)
        } else {
            ClassName(
                environment.elementUtils.getPackageOf(element).qualifiedName.toString(),
                simpleServiceName + suffix
            )
        }
        val qualifiedServiceName = if (annotation.packageName.isNotBlank()) {
            annotation.packageName + "." + simpleServiceName
        } else {
            environment.elementUtils.getPackageOf(element).qualifiedName.toString() + "." + simpleServiceName
        }

        // determine marshaller to use
        val marshallerType = try {
            annotation.marshaller.asTypeName()
        } catch (e: MirroredTypeException) {
            e.typeMirror.asTypeName()
        }

        // generate the client
        return FileSpec.builder(typeName.packageName, typeName.simpleName)
            .addFunction(
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
                    .returns(typeName)
                    .addStatement("return %T(this.build(), callOptions, coroutineScope)", typeName)
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
            )
            .addFunction(
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
                    .returns(typeName)
                    .addStatement("return %T(this, callOptions, coroutineScope)", typeName)
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
            .addType(
                TypeSpec.classBuilder(typeName)
                    .addKdoc(
                        """
                        |A gRPC client for [%T] governed by the [callOptions].
                        |
                        |Streaming methods are launched in the [coroutineScope], which is the [%T] by default.
                        """.trimMargin(),
                        interfaceType,
                        GlobalScope::class.asTypeName()
                    )
                    .addSuperinterface(interfaceType)
                    .addSuperinterface(AutoCloseable::class)
                    .primaryConstructor(
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
                    .addProperty(
                        PropertySpec.builder("channel", ManagedChannel::class)
                            .addModifiers(KModifier.PRIVATE)
                            .initializer("channel")
                            .build()
                    )
                    .addProperty(
                        PropertySpec.builder("callOptions", CallOptions::class)
                            .addModifiers(KModifier.PRIVATE)
                            .initializer("callOptions")
                            .build()
                    )
                    .addProperty(
                        PropertySpec.builder("coroutineScope", CoroutineScope::class.asClassName())
                            .addModifiers(KModifier.PRIVATE)
                            .initializer("coroutineScope")
                            .build()
                    )
                    .addProperties(element.extractRpcMethodDescriptors(kmetadata, qualifiedServiceName, marshallerType))
                    .addFunctions(element.extractRpcMethods(kmetadata))
                    .addFunction(
                        FunSpec.builder("close")
                            .addModifiers(KModifier.OVERRIDE)
                            .addStatement(
                                "channel.shutdown().awaitTermination(5, %T.SECONDS)",
                                TimeUnit::class.asTypeName()
                            )
                            .build()
                    )
                    .build()
            )
            .addImport("kotlin.coroutines", "resume", "resumeWithException")
            .addImport("kotlinx.coroutines", "launch", "suspendCancellableCoroutine")
            .addImport("kotlinx.coroutines.guava", "await")
            .build()
    }

    private fun Element.extractRpcMethods(metadata: KotlinClassMetadata.Class) =
        this.mapRPCs(metadata) { methodInfo, rpc ->
            val requestVar = methodInfo.parameters.first().name
            val requestType = methodInfo.parameters.first().type
            val callVar = "call".unless(requestVar)

            // TODO: support futures or something other than suspending functions?
            if (!methodInfo.isSuspend) {
                throw CodeGenerationException("Client methods must have the 'suspend' keyword.")
            }

            // build method
            val builder = FunSpec.builder(methodInfo.name)
            builder.addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                .addParameter(requestVar, requestType)
                .returns(methodInfo.returns)
                .addStatement(
                    "val %L = channel.newCall(this.%L, this.callOptions)",
                    callVar, rpc.descriptorPropertyName
                )

            // implement method based on rpc method type
            if (methodInfo.rpc.type == MethodDescriptor.MethodType.SERVER_STREAMING) {
                val dataVar = "data".unless(callVar, requestVar)
                val channelVar = "channel".unless(callVar, requestVar, dataVar)
                builder.addCode(
                    """
                    |val %L: %T = Channel()
                    |this.coroutineScope.launch {
                    |    suspendCancellableCoroutine { cont: %T ->
                    |        %T.asyncServerStreamingCall(call, %L, object : %T {
                    |            override fun onNext(value: %T) {
                    |                launch { %L.send(value) }
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
                    Channel::class.asClassName().parameterizedBy(methodInfo.rpc.outputType),
                    CancellableContinuation::class.asClassName().parameterizedBy(Unit::class.asTypeName()),
                    ClientCalls::class.asTypeName(), requestVar,
                    StreamObserver::class.asClassName().parameterizedBy(methodInfo.rpc.outputType),
                    methodInfo.rpc.outputType,
                    channelVar,
                    channelVar,
                    channelVar,
                    channelVar
                )
            } else if (methodInfo.rpc.type == MethodDescriptor.MethodType.CLIENT_STREAMING) {
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
                    CancellableContinuation::class.asClassName().parameterizedBy(methodInfo.rpc.outputType),
                    requestStreamVar, ClientCalls::class.asTypeName(),
                    StreamObserver::class.asClassName().parameterizedBy(methodInfo.rpc.outputType),
                    methodInfo.rpc.outputType,
                    requestVar,
                    requestStreamVar,
                    requestStreamVar,
                    CancellationException::class.asClassName(),
                    requestStreamVar,
                    requestStreamVar
                )
            } else if (methodInfo.rpc.type == MethodDescriptor.MethodType.BIDI_STREAMING) {
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
                    |                launch { %L.send(value) }
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
                    Channel::class.asClassName().parameterizedBy(methodInfo.rpc.outputType),
                    CancellableContinuation::class.asClassName().parameterizedBy(Unit::class.asTypeName()),
                    requestStreamVar, ClientCalls::class.asTypeName(),
                    StreamObserver::class.asClassName().parameterizedBy(methodInfo.rpc.outputType),
                    methodInfo.rpc.outputType,
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

            builder.build()
        }

    private fun Element.extractRpcMethodDescriptors(
        metadata: KotlinClassMetadata.Class,
        serviceName: String,
        marshallerType: TypeName
    ) =
        this.mapRPCs(metadata) { methodInfo, rpc ->
            val type = MethodDescriptor::class.asClassName()
                .parameterizedBy(methodInfo.rpc.inputType, methodInfo.rpc.outputType)

            PropertySpec.builder(rpc.descriptorPropertyName, type)
                .addModifiers(KModifier.PRIVATE)
                .delegate(
                    """
                    |lazy {
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
                    marshallerType, methodInfo.rpc.inputType,
                    marshallerType, methodInfo.rpc.outputType,
                    serviceName, methodInfo.rpc.name,
                    methodInfo.rpc.type.name
                )
                .build()
        }
}
