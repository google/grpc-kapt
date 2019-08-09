# gRPC-kapt

[![CircleCI](https://circleci.com/gh/google/grpc-kapt.svg?style=svg)](https://circleci.com/gh/google/grpc-kapt)

A Kotlin [kapt](https://kotlinlang.org/docs/reference/kapt.html) annotation processor for using
[gRPC](https://grpc.io/) with the transport of your choice (json, proto, etc.).

Supports client & server creation with [coroutines](https://kotlinlang.org/docs/reference/coroutines-overview.html).

*Note* This project is a preview. Please try it out and let us know what you think, but there 
are currently no guarantees of any form of stability or support.

## Getting started

To create a gRPC client simply create an interface annotated with `@GrpcClient`.

To implement a server, create another class that inherits from your `@GrpcClient`,
annotate it with `@GrpcServer`, and implement the server-side logic for the methods 
that you have defined on the client.

```kotlin
fun main() = runBlocking {
    // create the server (use .asGrpcService() to create long running servers)
    SimpleServer().asGrpcServer(8080) {
        // create a client and query the server
        SimpleServiceClient.forAddress("localhost", 8080, channelOptions = { usePlaintext() }).use { client ->
            val answer = client.ask(Question("what's this?"))
            println(answer)
        }
    }
}

// define the data types
data class Question(val query: String)
data class Answer(val result: String)

// generate a gRPC client
@GrpcClient
interface SimpleService {
    suspend fun ask(question: Question): Answer
}

// generate a gRPC service
@GrpcServer
class SimpleServer : SimpleService {
    override suspend fun ask(question: Question) = Answer(result = "you said: '${question.query}'")
}
```

In most cases, you should also add at least one object in your application with the `@GrpcMarshaller`
annotation to specify how the data will be marshaled. 

Here's another example using JSON and a bidirectional streaming API:

```kotlin
fun main() = runBlocking {
    // create the server
    ComplexServer().asGrpcServer(8080) {
        // create a client and call the server
        ComplexServiceClient.forAddress("localhost", 8080, channelOptions = { usePlaintext() }).use { client ->
            // bidirectional streaming
            val answers = client.debate(produce {
                repeat(10) { i -> send(Question("Question #${i + 1}")) }
            })
            for (answer in answers) {
                println(answer)
            }
        }
    }
}

// generate a gRPC client
@GrpcClient
interface ComplexService {
    suspend fun debate(questions: ReceiveChannel<Question>): ReceiveChannel<Answer>
}

// generate & implement a gRPC service
@GrpcServer
class ComplexServer : ComplexService {
    override suspend fun debate(questions: ReceiveChannel<Question>) = CoroutineScope(coroutineContext).produce {
        for (question in questions) {
            send(Answer("${question.query}? Sorry, I don't know..."))
        }
    }
}

// Use JSON serialization for the client & server
@GrpcMarshaller
object MyMarshallerProvider {
    private val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule())

    fun <T> of(type: Class<T>): MethodDescriptor.Marshaller<T> {
        return object : MethodDescriptor.Marshaller<T> {
            override fun stream(value: T): InputStream = ByteArrayInputStream(mapper.writeValueAsBytes(value))
            override fun parse(stream: InputStream): T = stream.bufferedReader().use { mapper.readValue(it, type) }
        }
    }
}
```

### More Examples

See the [complete example here](example-with-json), a [proto example here](example-with-proto), and a
[Google Cloud API example here](example-with-google-api).

For the adventurous, see the [gRPC reflection](https://github.com/grpc/grpc-java/blob/master/documentation/server-reflection-tutorial.md) 
example [here](example-with-proto/src/main/kotlin/com/google/api/example/ExampleReflection.kt).

You can run the examples by running the following in the root of the project:

```bash
 $ ./gradlew :runExample
 $ ./gradlew :runExampleWithJson
 $ ./gradlew :runExampleWithProto
 $ ./gradlew :runExampleWithGoogle
 $ ./gradlew :runExampleWithProtoReflection
```

*Note*: `:runExampleWithGoogle` requires a [GCP](https://cloud.google.com) project with the 
[Langauge API](https://cloud.google.com/natural-language/) enabled. Set the `GOOGLE_APPLICATION_CREDENTIALS`
environment variable before running, i.e.:

```bash
GOOGLE_APPLICATION_CREDENTIALS=<path_to_service_account.json> ./gradlew :runExampleWithGoogle
```

## Related Projects

It's sort of a simpler version of [kgen](https://github.com/googleapis/gapic-generator-kotlin).

## Contributing

Contributions to this library are always welcome and highly encouraged.

See the [CONTRIBUTING](CONTRIBUTING.md) documentation for more information on how to get started.

## Versioning

This library is currently a *preview* with no guarantees of stability or support. Please get involved and let us know
if you find it useful and we'll work towards a stable version.

## Disclaimer

This is not an official Google product.
