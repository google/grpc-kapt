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

import com.google.api.grpc.kapt.GrpcServer
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import io.grpc.BindableService
import io.grpc.MethodDescriptor
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerCallHandler
import io.grpc.ServerServiceDefinition
import io.grpc.ServiceDescriptor
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.metadata.jvm.KotlinClassMetadata
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.type.MirroredTypeException

internal class ServerGenerator(
    override val environment: ProcessingEnvironment,
    private val suffix: String = "ServerImpl"
) : Generator {
    override fun generate(element: Element): FileSpec {
        val annotation = element.getAnnotation(GrpcServer::class.java)
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

        return FileSpec.builder(typeName.packageName, typeName.simpleName)
            .addFunction(
                FunSpec.builder("asGrpcService")
                    .addParameter(
                        ParameterSpec.builder("coroutineScope", CoroutineScope::class)
                            .defaultValue("%T", GlobalScope::class)
                            .build()
                    )
                    .receiver(interfaceType)
                    .returns(BindableService::class)
                    .addStatement("return %T(this, coroutineScope)", typeName)
                    .addKdoc(
                        """
                        |Create a new service that can be bound to a gRPC server.
                        |
                        |Server methods are launched in the [coroutineScope], which is the [%T] by default.
                        """.trimMargin(),
                        GlobalScope::class.asTypeName()
                    )
                    .build()
            )
            .addFunction(
                FunSpec.builder("asGrpcServer")
                    .addParameter("port", Int::class)
                    .addParameter(
                        ParameterSpec.builder("coroutineScope", CoroutineScope::class)
                            .defaultValue("%T", GlobalScope::class)
                            .build()
                    )
                    .receiver(interfaceType)
                    .returns(Server::class)
                    .addStatement(
                        """
                    |return %T.forPort(port)
                    |    .addService(this.asGrpcService(coroutineScope))
                    |    .build()
                    """.trimMargin(),
                        ServerBuilder::class
                    )
                    .addKdoc(
                        """
                        |Create a new gRPC server on the given [port] running the [%T] service.
                        |
                        |Server methods are launched in the [coroutineScope], which is the [%T] by default.
                        """.trimMargin(),
                        interfaceType,
                        GlobalScope::class.asTypeName()
                    )
                    .build()
            )
            .addFunction(
                FunSpec.builder("asGrpcServer")
                    .addModifiers(KModifier.SUSPEND)
                    .addParameter("port", Int::class)
                    .addParameter(
                        "block",
                        LambdaTypeName.get(
                            receiver = CoroutineScope::class.asClassName(),
                            returnType = Unit::class.asTypeName()
                        ).copy(suspending = true)
                    )
                    .receiver(interfaceType)
                    .addCode(
                        """
                        |coroutineScope {
                        |    launch {
                        |        val server = this@asGrpcServer.asGrpcServer(port, this)
                        |        server.start()
                        |        block()
                        |        server.shutdown().awaitTermination()
                        |    }
                        |}
                        |""".trimMargin()
                    )
                    .addKdoc(
                        """
                        |Create a new gRPC server and execute the given block before shutting down.
                        |
                        |This method is intended for testing and examples.
                        |""".trimMargin()
                    )
                    .build()
            )
            .addTypeAlias(methodListTypeAlias)
            .addTypeAlias(methodBindingTypeAlias)
            .addFunction(methodBindingFun)
            .addType(
                TypeSpec.classBuilder(typeName)
                    .addModifiers(KModifier.PRIVATE)
                    .addSuperinterface(BindableService::class)
                    .primaryConstructor(
                        FunSpec.constructorBuilder()
                            .addParameter("implementation", interfaceType)
                            .addParameter("coroutineScope", CoroutineScope::class)
                            .build()
                    )
                    .addProperty(
                        PropertySpec.builder("implementation", interfaceType)
                            .addModifiers(KModifier.PRIVATE)
                            .initializer("implementation")
                            .build()
                    )
                    .addProperty(
                        PropertySpec.builder("name", String::class)
                            .addModifiers(KModifier.PRIVATE)
                            .initializer("%S", qualifiedServiceName)
                            .build()
                    )
                    .addProperty(element.generateMethodDescriptors(kmetadata, marshallerType))
                    .addProperty(generateServiceDescriptor())
                    .addProperty(generateServiceDefinition())
                    .addFunction(
                        FunSpec.builder("bindService")
                            .addModifiers(KModifier.OVERRIDE)
                            .returns(ServerServiceDefinition::class.asTypeName())
                            .addStatement("return serviceDefinition")
                            .build()
                    )
                    .build()
            )
            .addImport("kotlinx.coroutines", "launch")
            .addImport("kotlinx.coroutines", "coroutineScope")
            .build()
    }

    private fun Element.generateMethodDescriptors(
        metadata: KotlinClassMetadata.Class,
        marshallerType: TypeName
    ): PropertySpec {
        val methodsInitalizers = this.mapRPCs(metadata) { methodInfo, rpc ->
            val serverCall = with(CodeBlock.builder()) {
                indent()
                if (methodInfo.rpc.type == MethodDescriptor.MethodType.SERVER_STREAMING) {
                    add(
                        """
                        |%T.asyncServerStreamingCall { request: %T, responseObserver: %T ->
                        |    coroutineScope.launch {
                        |        try {
                        |            for (data in implementation.%L(request)) {
                        |                responseObserver.onNext(data)
                        |            }
                        |            responseObserver.onCompleted()
                        |        } catch(t: Throwable) {
                        |            responseObserver.onError(t)
                        |        }
                        |    }
                        |}
                        """.trimMargin(),
                        ServerCalls::class.asClassName(), methodInfo.rpc.inputType,
                        StreamObserver::class.asClassName().parameterizedBy(methodInfo.rpc.outputType),
                        methodInfo.name
                    )
                } else if (methodInfo.rpc.type == MethodDescriptor.MethodType.CLIENT_STREAMING) {
                    add(
                        """
                        |%T.asyncClientStreamingCall { responseObserver: %T ->
                        |    val requestChannel: %T = Channel()
                        |    val requestObserver = object : %T {
                        |        override fun onNext(value: %T) {
                        |            coroutineScope.launch { requestChannel.send(value) }
                        |        }
                        |
                        |        override fun onError(t: Throwable) {
                        |            requestChannel.close(t)
                        |        }
                        |
                        |        override fun onCompleted() {
                        |            requestChannel.close()
                        |        }
                        |    }
                        |
                        |    coroutineScope.launch {
                        |        responseObserver.onNext(implementation.%L(requestChannel))
                        |    }
                        |
                        |    requestObserver
                        |}
                        """.trimMargin(),
                        ServerCalls::class.asClassName(),
                        StreamObserver::class.asClassName().parameterizedBy(methodInfo.rpc.outputType),
                        Channel::class.asClassName().parameterizedBy(methodInfo.rpc.inputType),
                        StreamObserver::class.asClassName().parameterizedBy(methodInfo.rpc.inputType),
                        methodInfo.rpc.inputType,
                        methodInfo.name
                    )
                } else if (methodInfo.rpc.type == MethodDescriptor.MethodType.BIDI_STREAMING) {
                    add(
                        """
                        |%T.asyncBidiStreamingCall { responseObserver: %T ->
                        |    val requestChannel: %T = Channel()
                        |    val requestObserver = object : %T {
                        |        override fun onNext(value: %T) {
                        |            coroutineScope.launch { requestChannel.send(value) }
                        |        }
                        |
                        |        override fun onError(t: Throwable) {
                        |            requestChannel.close(t)
                        |        }
                        |
                        |        override fun onCompleted() {
                        |            requestChannel.close()
                        |        }
                        |    }
                        |
                        |    coroutineScope.launch {
                        |        try {
                        |            for (data in implementation.%L(requestChannel)) {
                        |                responseObserver.onNext(data)
                        |            }
                        |            responseObserver.onCompleted()
                        |        } catch(t: Throwable) {
                        |            responseObserver.onError(t)
                        |        }
                        |    }
                        |
                        |    requestObserver
                        |}
                        """.trimMargin(),
                        ServerCalls::class.asClassName(),
                        StreamObserver::class.asClassName().parameterizedBy(methodInfo.rpc.outputType),
                        Channel::class.asClassName().parameterizedBy(methodInfo.rpc.inputType),
                        StreamObserver::class.asClassName().parameterizedBy(methodInfo.rpc.inputType),
                        methodInfo.rpc.inputType,
                        methodInfo.name
                    )
                } else { // MethodDescriptor.MethodType.UNARY
                    add(
                        """
                        |%T.asyncUnaryCall { request: %T, responseObserver: %T ->
                        |    coroutineScope.launch {
                        |        try {
                        |            responseObserver.onNext(implementation.%L(request))
                        |            responseObserver.onCompleted()
                        |        } catch(t: Throwable) {
                        |            responseObserver.onError(t)
                        |        }
                        |    }
                        |}
                        """.trimMargin(),
                        ServerCalls::class.asClassName(), methodInfo.rpc.inputType,
                        StreamObserver::class.asClassName().parameterizedBy(methodInfo.rpc.outputType),
                        methodInfo.name
                    )
                }
                unindent()
            }.build()

            CodeBlock.of(
                """
                |methodBinding(
                |    with(
                |        %T.newBuilder(
                |            %T.of(%T::class.java),
                |            %T.of(%T::class.java)
                |        )
                |    ) {
                |        setFullMethodName(
                |            MethodDescriptor.generateFullMethodName(name, %S)
                |        )
                |        setType(MethodDescriptor.MethodType.%L)
                |        build()
                |    },
                |    %L
                |)
                """.trimMargin(),
                MethodDescriptor::class.asTypeName(),
                marshallerType, methodInfo.rpc.inputType,
                marshallerType, methodInfo.rpc.outputType,
                methodInfo.rpc.name,
                methodInfo.rpc.type.name,
                serverCall
            )
        }

        return PropertySpec.builder("methods", methodListTypeAlias.type)
            .addModifiers(KModifier.PRIVATE)
            .initializer(with(CodeBlock.builder()) {
                add("listOf(\n")
                indent()
                for ((idx, code) in methodsInitalizers.withIndex()) {
                    if (idx == methodsInitalizers.size - 1) {
                        addStatement("%L", code)
                    } else {
                        addStatement("%L,", code)
                    }
                }
                unindent()
                add(")")
                build()
            })
            .build()
    }

    private fun generateServiceDescriptor() =
        PropertySpec.builder("serviceDescriptor", ServiceDescriptor::class.asTypeName())
            .addModifiers(KModifier.PRIVATE)
            .initializer(
                """
            |with(
            |    %T.newBuilder(name)
            |) {
            |    for ((method, _) in methods) {
            |        addMethod(method)
            |    }
            |    build()
            |}
            |""".trimMargin(),
                ServiceDescriptor::class.asTypeName()
            )
            .build()

    private fun generateServiceDefinition() =
        PropertySpec.builder("serviceDefinition", ServerServiceDefinition::class.asTypeName())
            .addModifiers(KModifier.PRIVATE)
            .initializer(
                """
            |with(
            |    %T.builder(serviceDescriptor)
            |) {
            |    for ((method, handler) in methods) {
            |        @Suppress("UNCHECKED_CAST")
            |        addMethod(method as %T, handler as %T)
            |    }
            |    build()
            |}
            |""".trimMargin(),
                ServerServiceDefinition::class.asTypeName(),
                MethodDescriptor::class.asClassName().parameterizedBy(Any::class.asTypeName(), Any::class.asTypeName()),
                ServerCallHandler::class.asClassName().parameterizedBy(Any::class.asTypeName(), Any::class.asTypeName())
            )
            .build()
}

private val methodBindingTypeAlias = TypeAliasSpec.builder(
    "MethodBinding<ReqT, RespT>",
    Pair::class.asClassName().parameterizedBy(
        MethodDescriptor::class.asClassName().parameterizedBy(
            TypeVariableName("ReqT"), TypeVariableName("RespT")
        ),
        ServerCallHandler::class.asClassName().parameterizedBy(
            TypeVariableName("ReqT"), TypeVariableName("RespT")
        )
    )
)
    .addModifiers(KModifier.PRIVATE)
    .build()

private val methodListTypeAlias = TypeAliasSpec.builder(
    "MethodList",
    List::class.asClassName().parameterizedBy(TypeVariableName("MethodBinding<*, *>"))
)
    .addModifiers(KModifier.PRIVATE)
    .build()

private val methodBindingFun = FunSpec.builder("methodBinding")
    .addTypeVariable(TypeVariableName("ReqT"))
    .addTypeVariable(TypeVariableName("RespT"))
    .addParameter(
        "descriptor", MethodDescriptor::class.asClassName().parameterizedBy(
            TypeVariableName("ReqT"), TypeVariableName("RespT")
        )
    )
    .addParameter(
        "handler", ServerCallHandler::class.asClassName().parameterizedBy(
            TypeVariableName("ReqT"), TypeVariableName("RespT")
        )
    )
    .returns(methodBindingTypeAlias.type)
    .addStatement("return %T(descriptor, handler)", Pair::class.asClassName())
    .addModifiers(KModifier.PRIVATE)
    .build()