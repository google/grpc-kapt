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

import com.google.api.grpc.kapt.FallbackMarshallerProvider
import com.google.api.grpc.kapt.GrpcClient
import com.google.api.grpc.kapt.GrpcServer
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import io.grpc.MethodDescriptor
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.metadata.Flag
import kotlinx.metadata.Flags
import kotlinx.metadata.KmClassVisitor
import kotlinx.metadata.KmFunctionVisitor
import kotlinx.metadata.KmTypeVisitor
import kotlinx.metadata.KmValueParameterVisitor
import kotlinx.metadata.KmVariance
import kotlinx.metadata.jvm.KotlinClassHeader
import kotlinx.metadata.jvm.KotlinClassMetadata
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.type.MirroredTypeException
import javax.tools.Diagnostic

/** A generator component running in the annotation processor's [environment]. */
internal abstract class Generator<T>(
    val environment: ProcessingEnvironment
) {
    /** Generate the component for the given [element]. */
    abstract fun generate(element: Element): T

    /** Extract the type info from the annotation, using values from the [element] as needed. */
    protected fun GrpcClient.extractTypeInfo(
        element: Element,
        suffix: String,
        providers: List<GeneratedInterfaceProvider>
    ): AnnotatedTypeInfo =
        extractTypeInfo(element, name, packageName, definedBy, suffix, providers)

    /** Extract the type info from the annotation, using values from the [element] as needed. */
    protected fun GrpcServer.extractTypeInfo(
        element: Element,
        suffix: String
    ): AnnotatedTypeInfo =
        extractTypeInfo(element, name, packageName, "", suffix)

    private fun extractTypeInfo(
        element: Element,
        nameFromAnnotation: String,
        packageNameFromAnnotation: String,
        definedByFromAnnotation: String,
        suffix: String,
        providers: List<GeneratedInterfaceProvider> = listOf()
    ): AnnotatedTypeInfo {
        // determine appropriate names to use for the service
        val simpleServiceName = if (nameFromAnnotation.isNotBlank()) {
            nameFromAnnotation
        } else {
            element.asGeneratedInterfaceName()
        }
        val typeName = if (packageNameFromAnnotation.isNotBlank()) {
            ClassName(packageNameFromAnnotation, simpleServiceName + suffix)
        } else {
            ClassName(
                environment.elementUtils.getPackageOf(element).qualifiedName.toString(),
                simpleServiceName + suffix
            )
        }
        val qualifiedServiceName = if (packageNameFromAnnotation.isNotBlank()) {
            "$packageNameFromAnnotation.$simpleServiceName"
        } else if (definedByFromAnnotation.isNotBlank()) {
            val provider = providers.firstOrNull { it.isDefinedBy(definedByFromAnnotation) }
            provider?.findFullyQualifiedServiceName(definedByFromAnnotation)
                ?: throw CodeGenerationException("No suitable generator found for definedBy='$definedByFromAnnotation'")
        } else {
            environment.elementUtils.getPackageOf(element).qualifiedName.toString() + "." + simpleServiceName
        }

        return AnnotatedTypeInfo(
            simpleName = simpleServiceName,
            fullName = qualifiedServiceName,
            type = typeName
        )
    }

    companion object {
        var DEFAULT_MARSHALLER: TypeName = FallbackMarshallerProvider::class.asTypeName()
    }
}

/**
 * Used for a client/server interface type that is auto-generated (instead of written by the user).
 *
 * The associated [methodInfo] contains whatever additional metadata the client/server generators requires
 * that is not contained in the interface named [typeName].
 *
 * The map must contain an entry for each function in the type named by [typeName].
 *
 * The [typeName] must be in the [types] list along with any other supplemental generated types.
 */
internal data class GeneratedInterface(
    val typeName: String,
    val methodInfo: Map<FunSpec, KotlinMethodInfo> = mapOf(),
    val simpleTypeName: String? = null,
    val types: List<TypeSpec> = listOf()
) {
    fun asMethods(): List<KotlinMethodInfo> = methodInfo.values.toList()
}

/** Interface for generators that produce the @GrpcClient interface definition. */
internal interface GeneratedInterfaceProvider {
    /**
     * Tests if this generator is relevant for the value given in [GrpcClient.definedBy].
     */
    fun isDefinedBy(value: String): Boolean

    /**
     * Find the fully qualified name of the service.
     *
     * The [value] corresponds to the value provided in [GrpcClient.definedBy].
     */
    fun findFullyQualifiedServiceName(value: String): String
}

/**
 * Type information about a generated gRPC client/server.
 *
 * The [simpleName] is the same as the [type] without any suffix.
 * The [fullName] is used for the gRPC service name.
 */
internal data class AnnotatedTypeInfo(val simpleName: String, val fullName: String, val type: ClassName)

/** Generic exception for [Generator] components to throw. */
internal class CodeGenerationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// logging helpers
internal fun ProcessingEnvironment.error(message: String) = this.messager.printMessage(Diagnostic.Kind.ERROR, message)

internal fun ProcessingEnvironment.warn(message: String) = this.messager.printMessage(Diagnostic.Kind.WARNING, message)

/** Class name used for the gRPC generated type (client & server). */
internal fun Element.asGeneratedInterfaceName() = this.simpleName.toString()

/** Class type used for the gRPC generated type (client & server). */
internal fun Element.asGeneratedInterfaceType() = this.asType().asTypeName()

/** Parse the marshaller provider from the annotation class. */
internal fun GrpcClient.asMarshallerType(): TypeName = try {
    this.marshaller.asTypeName()
} catch (e: MirroredTypeException) {
    e.typeMirror.asTypeName()
}.asMarshallerClass()

/** Parse the marshaller provider from the annotation class. */
internal fun GrpcServer.asMarshallerType(): TypeName = try {
    this.marshaller.asTypeName()
} catch (e: MirroredTypeException) {
    e.typeMirror.asTypeName()
}.asMarshallerClass()

private fun TypeName.asMarshallerClass() = if (Unit::class.asTypeName() == this) {
    Generator.DEFAULT_MARSHALLER
} else {
    this
}

/**
 * Extract the Kotlin compiler metadata from the [Element].
 *
 * The element must correspond to a Class or a [CodeGenerationException] will be thrown.
 */
internal fun Element.asKotlinMetadata(): KotlinClassMetadata.Class {
    val annotation = this.getAnnotation(Metadata::class.java)
        ?: throw CodeGenerationException("Kotlin metadata not found on element: ${this.simpleName}")

    return when (val metadata = KotlinClassMetadata.read(
        KotlinClassHeader(
            kind = annotation.kind,
            metadataVersion = annotation.metadataVersion,
            bytecodeVersion = annotation.bytecodeVersion,
            data1 = annotation.data1,
            data2 = annotation.data2,
            extraString = annotation.extraString,
            packageName = annotation.packageName,
            extraInt = annotation.extraInt
        )
    )) {
        is KotlinClassMetadata.Class -> metadata
        else -> throw CodeGenerationException("Kotlin metadata of unexpected type or unreadable")
    }
}

/** Get all the methods on this element that are candidate for RPC calls */
internal fun Element.filterRpcMethods(): List<ExecutableElement> =
    this.enclosedElements
        .filter { it.kind == ElementKind.METHOD }
        .mapNotNull { it as? ExecutableElement }
        .filter { it.parameters.size > 0 } // TODO: better filtering?

/** Info about a Kotlin function (including info extracted from the @Metadata annotation). */
internal data class KotlinMethodInfo(
    val name: String,
    val isSuspend: Boolean,
    val parameters: List<ParameterInfo>,
    val returns: TypeName,
    val rpc: RpcInfo
)

/** Info about the RPC associated with a method. */
internal data class RpcInfo(
    val name: String,
    val type: MethodDescriptor.MethodType,
    val inputType: TypeName,
    val outputType: TypeName
)

/** Parse information about a Kotlin function from the [kotlin.Metadata] annotation. */
internal fun KotlinClassMetadata.Class.describeElement(
    method: ExecutableElement
): KotlinMethodInfo {
    val funName = method.simpleName.toString()

    var isSuspend = false
    val parameters = mutableListOf<ParameterInfo>()
    var returns: TypeName = Unit::class.asTypeName()

    this.accept(object : KmClassVisitor() {
        override fun visitFunction(flags: Flags, name: String): KmFunctionVisitor? {
            if (funName != name) return null

            isSuspend = Flag.Function.IS_SUSPEND(flags)
            return object : KmFunctionVisitor() {
                private val returnTypeVisitor = TypeArgVisitor()

                override fun visitValueParameter(flags: Flags, name: String): KmValueParameterVisitor? {
                    val paramName = name
                    val paramTypeVisitor = TypeArgVisitor()

                    return object : KmValueParameterVisitor() {
                        override fun visitType(flags: Flags): KmTypeVisitor {
                            return paramTypeVisitor
                        }

                        override fun visitEnd() {
                            val type = paramTypeVisitor.type
                                ?: throw CodeGenerationException("Unable to determine param type of '$paramName' in method: '$funName'.")
                            parameters.add(ParameterInfo(paramName, type))
                        }
                    }
                }

                override fun visitReturnType(flags: Flags): KmTypeVisitor? {
                    return returnTypeVisitor
                }

                override fun visitEnd() {
                    returns = returnTypeVisitor.type
                        ?: throw CodeGenerationException("Unable to determine return type of method: '$funName'.")
                }
            }
        }
    })

    // sanity checks
    if (parameters.size != 1) {
        throw CodeGenerationException("Unexpected number of parameters (${parameters.size}) in method ${method.simpleName}")
    }

    // determine rpc type from method signature
    val inputType = parameters.first().type
    val methodType = when {
        returns.isReceiveChannel() && inputType.isReceiveChannel() -> MethodDescriptor.MethodType.BIDI_STREAMING
        returns.isReceiveChannel() -> MethodDescriptor.MethodType.SERVER_STREAMING
        inputType.isReceiveChannel() -> MethodDescriptor.MethodType.CLIENT_STREAMING
        else -> MethodDescriptor.MethodType.UNARY
    }

    return KotlinMethodInfo(
        name = funName,
        isSuspend = isSuspend,
        parameters = parameters,
        returns = returns,
        rpc = RpcInfo(
            name = funName.capitalize(),
            type = methodType,
            inputType = when (methodType) {
                MethodDescriptor.MethodType.CLIENT_STREAMING -> inputType.extractStreamType()
                MethodDescriptor.MethodType.BIDI_STREAMING -> inputType.extractStreamType()
                else -> inputType
            },
            outputType = when (methodType) {
                MethodDescriptor.MethodType.SERVER_STREAMING -> returns.extractStreamType()
                MethodDescriptor.MethodType.BIDI_STREAMING -> returns.extractStreamType()
                else -> returns
            }
        )
    )
}

// Helper for constructing a full type with arguments
private class TypeArgVisitor : KmTypeVisitor() {
    private var typeName: TypeName? = null
    private var className: ClassName? = null
    private val args = mutableListOf<TypeArgVisitor>()

    val type: TypeName?
        get() = typeName

    override fun visitClass(name: kotlinx.metadata.ClassName) {
        className = name.asClassName()
    }

    override fun visitArgument(flags: Flags, variance: KmVariance): KmTypeVisitor? {
        val nested = TypeArgVisitor()
        args += nested
        return nested
    }

    override fun visitEnd() {
        typeName = if (args.isNotEmpty()) {
            val typeArgs = args.mapNotNull { it.className }
            className?.parameterizedBy(*typeArgs.toTypedArray())
        } else {
            className
        }
    }
}

/** Test if the type is a [ReceiveChannel]. */
internal fun TypeName.isReceiveChannel() = this.isRawType(ReceiveChannel::class.asTypeName())

/** Test if the given [type] matches this type. */
internal fun TypeName.isRawType(type: TypeName): Boolean {
    val rawType = if (this is ParameterizedTypeName) {
        this.rawType
    } else {
        this
    }

    return rawType == type
}

/** Extracts the type that should be used for the rpc (first type argument) */
internal fun TypeName.extractStreamType(): TypeName {
    val t = this as? ParameterizedTypeName ?: throw CodeGenerationException("Invalid type for streaming method: $this.")

    if (t.typeArguments.size != 1) {
        throw CodeGenerationException("Invalid type arguments for streaming method (expected 1).")
    }

    return t.typeArguments.first()
}

/** Convert from a Kotlin [kotlinx.metadata.ClassName] to a KotlinPoet [ClassName]. */
internal fun kotlinx.metadata.ClassName.asClassName() = ClassName.bestGuess(this.replace("/", "."))

/** Describes a named parameter in a function of the given [name] and [type]. */
internal data class ParameterInfo(val name: String, val type: TypeName)

/** Ensures the string does not equal any of the [others]. */
internal fun String.unless(vararg others: String): String = if (others.contains(this)) {
    "_$this".unless(*others)
} else {
    this
}
