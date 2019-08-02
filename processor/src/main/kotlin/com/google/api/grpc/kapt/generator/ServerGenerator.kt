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
import com.google.api.grpc.kapt.GrpcServer
import com.google.api.grpc.kapt.generator.proto.ProtoInterfaceGenerator
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
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element

/**
 * Generates a gRPC [io.grpc.BindableService] from an interface annotated with [GrpcServer].
 */
internal class ServerGenerator(
    environment: ProcessingEnvironment,
    private val clientElements: Collection<Element>
) : Generator<List<FileSpec>>(environment) {
    private val protoGenerator by lazy {
        ProtoInterfaceGenerator(environment)
    }

    override fun generate(element: Element): List<FileSpec> {
        val files = mutableListOf<FileSpec>()

        val annotation = element.getAnnotation(GrpcServer::class.java)
            ?: throw CodeGenerationException("Unsupported element: $element")

        val interfaceType = element.asGeneratedInterfaceType()

        // get metadata required for generation
        val kmetadata = element.asKotlinMetadata()
        val typeInfo = annotation.extractTypeInfo(element, annotation.suffix)

        // determine marshaller to use
        val marshallerType = annotation.asMarshallerType()

        // determine the full service name (use the implemented interfaces, if available)
        val clientInterfaceTypeInfo = getClientAnnotation(element)
        val fullServiceName = clientInterfaceTypeInfo?.fullName ?: typeInfo.fullName
        val provider = clientInterfaceTypeInfo?.source ?: typeInfo.source

        // create the server
        val server = FileSpec.builder(typeInfo.type.packageName, typeInfo.type.simpleName)
            .addFunction(
                FunSpec.builder("asGrpcService")
                    .addParameter(
                        ParameterSpec.builder("coroutineScope", CoroutineScope::class)
                            .defaultValue("%T", GlobalScope::class)
                            .build()
                    )
                    .receiver(interfaceType)
                    .returns(BindableService::class)
                    .addStatement("return %T(this, coroutineScope)", typeInfo.type)
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
                TypeSpec.classBuilder(typeInfo.type)
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
                            .initializer("%S", fullServiceName)
                            .build()
                    )
                    .addProperty(
                        element.filterRpcMethods().map {
                            kmetadata.describeElement(it)
                        }.asMethodDescriptor(marshallerType, provider)
                    )
                    .addProperty(generateServiceDescriptor(provider))
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
            .addImport("kotlinx.coroutines", "launch", "runBlocking")
            .addImport("kotlinx.coroutines", "coroutineScope")

        val schemaDescriptorType = provider?.getSchemaDescriptorType()
        if (schemaDescriptorType != null) {
            server.addType(schemaDescriptorType)
        }

        files += server.build()

        return files
    }

    private fun List<KotlinMethodInfo>.asMethodDescriptor(
        marshallerType: TypeName,
        provider: SchemaDescriptorProvider?
    ): PropertySpec {
        val methodsInitializers = this.map { it.asMethodDescriptor(marshallerType, provider) }

        return PropertySpec.builder("methods", methodListTypeAlias.type)
            .addModifiers(KModifier.PRIVATE)
            .initializer(with(CodeBlock.builder()) {
                add("listOf(\n")
                indent()
                for ((idx, code) in methodsInitializers.withIndex()) {
                    if (idx == methodsInitializers.size - 1) {
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

    private fun KotlinMethodInfo.asMethodDescriptor(
        marshallerType: TypeName,
        provider: SchemaDescriptorProvider?
    ): CodeBlock {
        val serverCall = with(CodeBlock.builder()) {
            indent()
            if (rpc.type == MethodDescriptor.MethodType.SERVER_STREAMING) {
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
                    ServerCalls::class.asClassName(), rpc.inputType,
                    StreamObserver::class.asClassName().parameterizedBy(rpc.outputType),
                    name
                )
            } else if (rpc.type == MethodDescriptor.MethodType.CLIENT_STREAMING) {
                add(
                    """
                    |%T.asyncClientStreamingCall { responseObserver: %T ->
                    |    val requestChannel: %T = Channel()
                    |    val requestObserver = object : %T {
                    |        override fun onNext(value: %T) {
                    |            runBlocking { requestChannel.send(value) }
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
                    |            responseObserver.onNext(implementation.%L(requestChannel))
                    |        } finally {
                    |            responseObserver.onCompleted()
                    |        }
                    |    }
                    |
                    |    requestObserver
                    |}
                    """.trimMargin(),
                    ServerCalls::class.asClassName(),
                    StreamObserver::class.asClassName().parameterizedBy(rpc.outputType),
                    Channel::class.asClassName().parameterizedBy(rpc.inputType),
                    StreamObserver::class.asClassName().parameterizedBy(rpc.inputType),
                    rpc.inputType,
                    name
                )
            } else if (rpc.type == MethodDescriptor.MethodType.BIDI_STREAMING) {
                add(
                    """
                    |%T.asyncBidiStreamingCall { responseObserver: %T ->
                    |    val requestChannel: %T = Channel()
                    |    val requestObserver = object : %T {
                    |        override fun onNext(value: %T) {
                    |            runBlocking { requestChannel.send(value) }
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
                    StreamObserver::class.asClassName().parameterizedBy(rpc.outputType),
                    Channel::class.asClassName().parameterizedBy(rpc.inputType),
                    StreamObserver::class.asClassName().parameterizedBy(rpc.inputType),
                    rpc.inputType,
                    name
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
                    ServerCalls::class.asClassName(), rpc.inputType,
                    StreamObserver::class.asClassName().parameterizedBy(rpc.outputType),
                    name
                )
            }
            unindent()
        }.build()

        return CodeBlock.of(
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
            |        setSchemaDescriptor(%L)
            |        build()
            |    },
            |    %L
            |)
            """.trimMargin(),
            MethodDescriptor::class.asTypeName(),
            marshallerType, rpc.inputType,
            marshallerType, rpc.outputType,
            rpc.name,
            rpc.type.name,
            provider?.getSchemaDescriptorFor(rpc.name) ?: "null",
            serverCall
        )
    }

    private fun generateServiceDescriptor(provider: SchemaDescriptorProvider?) =
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
                |    setSchemaDescriptor(%L)
                |    build()
                |}
                |""".trimMargin(),
                ServiceDescriptor::class.asTypeName(),
                provider?.getSchemaDescriptorFor() ?: "null"
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

    // get the matching @GrpcClient annotation for this element (if it exists)
    private fun getClientAnnotation(element: Element): AnnotatedTypeInfo? {
        for (superType in environment.typeUtils.directSupertypes(element.asType())) {
            val clientInterface = clientElements.firstOrNull {
                environment.typeUtils.isSameType(it.asType(), superType)
            }
            val clientAnnotation = clientInterface?.getAnnotation(GrpcClient::class.java)
            if (clientAnnotation != null) {
                return clientAnnotation.extractTypeInfo(clientInterface, "")
            }
        }
        return null
    }

    private fun GrpcClient.extractTypeInfo(
        element: Element,
        suffix: String
    ): AnnotatedTypeInfo = extractTypeInfo(element, suffix, listOf(protoGenerator))
}

// various type aliases and help functions to simplify the generated code

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
