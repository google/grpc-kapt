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

package com.google.api.grpc.kapt.generator.proto

import com.google.api.grpc.kapt.GrpcClient
import com.google.api.grpc.kapt.generator.ClientGenerator
import com.google.api.grpc.kapt.generator.CodeGenerationException
import com.google.api.grpc.kapt.generator.GeneratedInterface
import com.google.api.grpc.kapt.generator.GeneratedInterfaceProvider
import com.google.api.grpc.kapt.generator.Generator
import com.google.api.grpc.kapt.generator.KotlinMethodInfo
import com.google.api.grpc.kapt.generator.ParameterInfo
import com.google.api.grpc.kapt.generator.RpcInfo
import com.google.protobuf.DescriptorProtos
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asTypeName
import io.grpc.MethodDescriptor
import kotlinx.coroutines.channels.ReceiveChannel
import java.io.File
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element

internal const val KAPT_PROTO_DESCRIPTOR_OPTION_NAME = "protos"

/**
 * Generates an interface from a protocol buffer file descriptor that can be consumed
 * by the other code generators.
 */
internal class ProtoInterfaceGenerator(
    environment: ProcessingEnvironment
) : Generator<GeneratedInterface>(environment), GeneratedInterfaceProvider {

    // read the descriptor from the kapt options
    private val descriptor: DescriptorProtos.FileDescriptorSet by lazy {
        val descriptorFile = File(
            environment.options[KAPT_PROTO_DESCRIPTOR_OPTION_NAME]
                ?: throw CodeGenerationException("The protobuf file descriptor set is not available to the annotation processor. Please set the katp '$KAPT_PROTO_DESCRIPTOR_OPTION_NAME' option.")
        )
        DescriptorProtos.FileDescriptorSet.parseFrom(descriptorFile.readBytes())
    }

    // create mappings from proto to Kotlin types
    private val typeMapper by lazy {
        ProtobufTypeMapper.fromProtos(descriptor.fileList)
    }

    override fun generate(element: Element): GeneratedInterface {
        val annotation = element.getAnnotation(GrpcClient::class.java)
            ?: throw CodeGenerationException("expected @GrpcClient element")

        // find the service
        val serviceInfo = findServiceOrNull(annotation.definedBy)
            ?: throw CodeGenerationException("service defined by '${annotation.definedBy}' was not found in the descriptor set.")
        val file = serviceInfo.file
        val service = serviceInfo.service

        // create the interface type
        val typeBuilder = TypeSpec.interfaceBuilder(service.name)
            .addSuperinterface(element.asType().asTypeName())
            .addType(
                TypeSpec.companionObjectBuilder()
                    .addFunctions(ClientGenerator.createChannelBuilders("${service.name}Ext", service.name))
                    .build()
            )
        val funMap = mutableMapOf<FunSpec, KotlinMethodInfo>()

        // add all the functions
        for (method in service.methodList) {
            val (func, metadata) = method.asClientMethod(typeMapper, file, service)
            typeBuilder.addFunction(func)
            funMap[func] = metadata
        }

        val extendedTypeBuilder = TypeSpec.interfaceBuilder("${service.name}Ext")
            .addSuperinterface(TypeVariableName(service.name))
            // .addSuperinterface(ClassName("", service.name))
            .addSuperinterface(AutoCloseable::class)
            .addProperties(ClientGenerator.COMMON_INTERFACE_PROPERTIES)

        return GeneratedInterface(
            typeName = "${service.name}Ext",
            simpleTypeName = service.name,
            methodInfo = funMap,
            types = listOf(typeBuilder.build(), extendedTypeBuilder.build())
        )
    }

    data class ServiceInfo(
        val file: DescriptorProtos.FileDescriptorProto,
        val service: DescriptorProtos.ServiceDescriptorProto
    ) {
        fun asFullyQualifiedServiceName() = "${file.`package`}.${service.name}"
    }

    private fun findServiceOrNull(name: String): ServiceInfo? {
        for (file in descriptor.fileList) {
            val service = file.serviceList.firstOrNull { "${file.`package`}.${it.name}" == name }
            if (service != null) {
                return ServiceInfo(file, service)
            }
        }
        return null
    }

    override fun isDefinedBy(value: String): Boolean = value.isNotBlank()

    override fun findFullyQualifiedServiceName(name: String): String =
        this.findServiceOrNull(name)?.asFullyQualifiedServiceName()
            ?: throw CodeGenerationException("no service found with name: '$name'")
}

private fun DescriptorProtos.MethodDescriptorProto.asClientMethod(
    typeMapper: ProtobufTypeMapper,
    file: DescriptorProtos.FileDescriptorProto,
    service: DescriptorProtos.ServiceDescriptorProto
): Pair<FunSpec, KotlinMethodInfo> {
    val kotlinOutputType = getKotlinOutputType(typeMapper)
    val kotlinInputType = getKotlinInputType(typeMapper)

    val requestVar = "request"
    val builder = FunSpec.builder(name.decapitalize())
        .addModifiers(KModifier.SUSPEND, KModifier.ABSTRACT)
        .returns(kotlinOutputType)
        .addParameter(requestVar, kotlinInputType)

    val comments = file.getMethodComments(service, this) ?: ""
    if (comments.isNotBlank()) {
        builder.addKdoc(comments)
    }

    val methodInfo = KotlinMethodInfo(
        name = name.decapitalize(),
        isSuspend = true,
        parameters = listOf(ParameterInfo(requestVar, kotlinInputType)),
        returns = kotlinOutputType,
        rpc = RpcInfo(
            name = name,
            type = when {
                serverStreaming && clientStreaming -> MethodDescriptor.MethodType.BIDI_STREAMING
                serverStreaming -> MethodDescriptor.MethodType.SERVER_STREAMING
                clientStreaming -> MethodDescriptor.MethodType.CLIENT_STREAMING
                else -> MethodDescriptor.MethodType.UNARY
            },
            inputType = typeMapper.getKotlinType(inputType),
            outputType = typeMapper.getKotlinType(outputType)
        )
    )

    return Pair(builder.build(), methodInfo)
}

private val RECEIVE_CHANNEL = ReceiveChannel::class.asTypeName()

private fun DescriptorProtos.MethodDescriptorProto.getKotlinOutputType(typeMapper: ProtobufTypeMapper): TypeName {
    val baseType = typeMapper.getKotlinType(outputType)
    return if (serverStreaming) {
        RECEIVE_CHANNEL.parameterizedBy(baseType)
    } else {
        baseType
    }
}

private fun DescriptorProtos.MethodDescriptorProto.getKotlinInputType(typeMapper: ProtobufTypeMapper): TypeName {
    val baseType = typeMapper.getKotlinType(inputType)
    return if (clientStreaming) {
        RECEIVE_CHANNEL.parameterizedBy(baseType)
    } else {
        baseType
    }
}

/** Get the comments of a service method in this .proto file, or null if not available */
internal fun DescriptorProtos.FileDescriptorProto.getMethodComments(
    service: DescriptorProtos.ServiceDescriptorProto,
    method: DescriptorProtos.MethodDescriptorProto
): String? {
    // find the magic numbers
    val serviceNumber = this.serviceList.indexOf(service)
    val methodNumber = service.methodList.indexOf(method)

    // location is [6, serviceNumber, 2, methodNumber]
    return this.sourceCodeInfo.locationList.filter {
        it.pathCount == 4 &&
            it.pathList[0] == 6 && // 6 is for service
            it.pathList[1] == serviceNumber &&
            it.pathList[2] == 2 && // 2 is for method (rpc)
            it.pathList[3] == methodNumber &&
            it.hasLeadingComments()
    }.map { it.leadingComments }.firstOrNull()
}
