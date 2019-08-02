# gRPC-kapt

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

In both cases, you should also add at least one object in your application with the `@GrpcMarshaller`
annotation. The example below uses Jackson for JSON serialization, but you may use
any framework that you like (Jackson, GSON, proto, etc.). For example, see the 
[proto-based example](example-with-proto).

```kotlin
fun main() = runBlocking {
    // create the server
    ComplexServer().asGrpcServer(PORT) {
        println("Server started on port: $PORT\n")

        // create a client with a new channel and call the server
        ComplexServiceClient.forAddress("localhost", PORT, channelOptions = {
            usePlaintext()
        }).use { client ->
            // unary call
            println(client.ask(Question("what's this?")))

            // bidirectional streaming
            val answers = client.debate(produce {
                repeat(10) { i -> send(Question("Question #${i + 1}")) }
            })
            for (answer in answers) {
                println(answer)
            }
        }
    }
    println("Server terminated.")
}

// define the data types
data class Question(val query: String)
data class Answer(val result: String)

// generate a gRPC client
@GrpcClient
interface ComplexService {
    suspend fun ask(question: Question): Answer
    suspend fun debate(questions: ReceiveChannel<Question>): ReceiveChannel<Answer>
}

// generate & implement a gRPC service
@GrpcServer
class ComplexServer : ComplexService {

    override suspend fun ask(question: Question) = Answer("you said: '${question.query}'")

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

See the [complete example here](example-with-json), a [proto example here](example-with-proto), and a
[Google Cloud API example here](example-with-google-api).

For the adventurous, see the [gRPC reflection](https://github.com/grpc/grpc-java/blob/master/documentation/server-reflection-tutorial.md) 
example [here](example-with-proto/src/main/kotlin/com/google/api/example/ExampleReflection.kt).

It's sort of a simpler version of [kgen](https://github.com/googleapis/gapic-generator-kotlin).

## Contributing

Contributions to this library are always welcome and highly encouraged.

See the [CONTRIBUTING](CONTRIBUTING.md) documentation for more information on how to get started.

## Versioning

This library is currently a *preview* with no guarantees of stability or support. Please get involved and let us know
if you find it useful and we'll work towards a stable version.

## Disclaimer

This is not an official Google product.
