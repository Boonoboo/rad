package dk.cachet.kodegen

import com.google.auto.service.AutoService
import net.ltgt.gradle.incap.IncrementalAnnotationProcessor
import net.ltgt.gradle.incap.IncrementalAnnotationProcessorType

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.classinspector.elements.ElementsClassInspector
import com.squareup.kotlinpoet.metadata.*
import com.squareup.kotlinpoet.metadata.specs.toTypeSpec

import io.ktor.application.Application
import io.ktor.client.HttpClient

import kotlinx.serialization.ContextualSerialization
import kotlinx.serialization.Serializable

import java.io.File
import javax.annotation.processing.*
import javax.lang.model.SourceVersion
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.tools.Diagnostic

/**
 * Used for generation of Ktor artifacts based on elements tagged with [ApplicationService] and [RequireAuthentication].
 */
@KotlinPoetMetadataPreview
@AutoService(Processor::class)
@IncrementalAnnotationProcessor(IncrementalAnnotationProcessorType.AGGREGATING)
class KodegenProcessor : AbstractProcessor() {
	companion object {
		const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"

		val KOTLIN_BUILTIN_SERIALIZERS = listOf("kotlin.Char","kotlin.String","kotlin.Byte","kotlin.ByteArray",
			"kotlin.Boolean","kotlin.Double", "kotlin.Float", "kotlin.Int","kotlin.Long","kotlin.Short","kotlin.Pair",
			"kotlin.Triple","kotlin.collections.Map.Entry", "kotlin.MapEntry","kotlin.Array","kotlin.collections.List",
			"kotlin.collections.Set","kotlin.collections.Map")

		val AUTOGENERATED_COMMENT = "This code was generated by Kodegen.\r\n" +
				"Changes to the file may cause incorrect behaviour and will be lost " +
				"if the code is regenerated."
	}

	override fun getSupportedAnnotationTypes(): Set<String> {
		return setOf("dk.cachet.kodegen.ApplicationService", "dk.cachet.kodegen.RequireAuthentication")
	}

	override fun getSupportedSourceVersion(): SourceVersion {
		return SourceVersion.latestSupported()
	}

	override fun getSupportedOptions(): Set<String> {
		return setOf(KAPT_KOTLIN_GENERATED_OPTION_NAME)
	}

	// Holds a service name / method name pair of methods that should be authenticated
	private val authenticatedMethods = mutableListOf<Pair<String, String>>()

	override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
		// Check if generated source root exists, and if not, return with an error message
		val generatedSourcesRoot: String = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME].orEmpty()
		if (generatedSourcesRoot.isEmpty()) {
			processingEnv.messager.printMessage(
				Diagnostic.Kind.ERROR,
				"Can't find the target directory for generated Kotlin files."
			)
			return false
		}

		// Populate authenticatedMethods with methods annotated with RequireAuthentication
		roundEnv.getElementsAnnotatedWith(RequireAuthentication::class.java).forEach { element ->
			val methodElement = element as ExecutableElement
			val enclosingClass = methodElement.enclosingElement as TypeElement
			authenticatedMethods += Pair(enclosingClass.simpleName.toString(), methodElement.simpleName.toString())
		}

		// Iterate through types annotated with ApplicationService, generating artifacts for each
		roundEnv.getElementsAnnotatedWith(ApplicationService::class.java).forEach {
			val serviceElement = it as TypeElement

			// Get the package of the service, which will be used for the generated components
			val servicePackage = processingEnv.elementUtils.getPackageOf(serviceElement).toString()

			// Generate a KotlinPoet TypeSpec from the TypeElement, allowing Kotlin-specific information to be derived
			// Using Javax Annotation Processing and Kotlin Poet to derive parameter information for methods
			// may result in use of Java types instead of Kotlin types
			// Generating TypeSpecs ensures that the correct types are used
			// An ElementsClassInspector is used to assist in analyzing the classes when generating the TypeSpec
			val classInspector = ElementsClassInspector.create(processingEnv.elementUtils, processingEnv.typeUtils)
			val serviceTypeSpec = serviceElement.toTypeSpec(classInspector)

			// Generate the components
			generateTransferObjects(serviceTypeSpec, servicePackage, generatedSourcesRoot)
			generateServerEndpoint(serviceTypeSpec, servicePackage, generatedSourcesRoot)
			generateServiceInvoker(serviceTypeSpec, servicePackage, generatedSourcesRoot)
		}
		return false
	}

	private fun generateTransferObjects(serviceTypeSpec: TypeSpec,
										servicePackage: String,
										generatedSourcesRoot: String) {

		val targetPackage = "$servicePackage.kodegen"
		val fileName = "${serviceTypeSpec.name!!.capitalize()}Objects"

		val file = File(generatedSourcesRoot)
		file.mkdir()
		val fileSpecBuilder = FileSpec.builder(targetPackage, fileName)
			.addComment(AUTOGENERATED_COMMENT)

		// Iterate through all function of the service
		// For each function, generate a request object if it takes at least one parameter,
		// and a response object if it has a return type
		serviceTypeSpec.funSpecs.forEach { funSpec ->
			if(funSpec.parameters.isNotEmpty()) {
				// Define name and package of generated class
				val requestObjectName = "${serviceTypeSpec.name!!.capitalize()}${funSpec.name.capitalize()}Request"
				val serviceClassName = ClassName(targetPackage, requestObjectName)

				val typeBuilder = TypeSpec.classBuilder(serviceClassName)
					.addModifiers(KModifier.DATA)
					.addAnnotation(Serializable::class)

				val constructorBuilder = FunSpec.constructorBuilder()

				// Add the parameters of the function to the request object's properties and constructor parameters
				funSpec.parameters.forEach { parameter ->
					// Find the parameter type and check if a serializer can be found at compile-time
					// If not, add ContextualSerialization
					var parameterType = getTypeWithContextualSerialization(parameter.type)
					if(parameter.type.isNullable)
					{
						parameterType = parameterType.copy(nullable = true)
					}
					val propertySpec = PropertySpec.builder(parameter.name, parameterType)
						.initializer(parameter.name)
						.build()

					constructorBuilder.addParameter(parameter.name, parameterType)

					typeBuilder.addProperty(propertySpec)
				}

				typeBuilder
					.primaryConstructor(constructorBuilder.build())

				fileSpecBuilder.addType(typeBuilder.build())
			}
			if (funSpec.returnType != null) {
				val responseObjectName = "${serviceTypeSpec.name!!.capitalize()}${funSpec.name.capitalize()}Response"

				// Find the parameter type and check if a serializer can be found at compile-time
				// If not, add ContextualSerialization

				var resultType = getTypeWithContextualSerialization(funSpec.returnType!!)
				if(funSpec.returnType!!.isNullable)
				{
					resultType = resultType.copy(nullable = true)
				}


				val result = PropertySpec.builder("result", resultType)
					.initializer("result")
					.build()

				val responseTypeBuilder = TypeSpec.classBuilder(ClassName(targetPackage, responseObjectName))
					.addModifiers(KModifier.DATA)
					.addAnnotation(Serializable::class)
					.primaryConstructor(FunSpec.constructorBuilder()
						.addParameter("result", resultType)
						.build())
					.addProperty(result)



				fileSpecBuilder.addType(responseTypeBuilder.build())
			}
		}

		fileSpecBuilder
			.build()
			.writeTo(file)
	}

	private fun getTypeWithContextualSerialization(type: TypeName): TypeName {
		val contextualSerializationSpec = AnnotationSpec.builder(ContextualSerialization::class.asTypeName()).build()
		val typeElement = processingEnv.elementUtils.getTypeElement(type.toString())

		var newType = type

		if(type is ParameterizedTypeName)
		{
			val typeArguments = type.typeArguments.map { getTypeWithContextualSerialization(it) }
			newType = ClassName(type.rawType.packageName, type.rawType.simpleName).parameterizedBy(typeArguments)

			// Check if the type is serializable, has a built-in serializer or is nullable and has a built-in serializer
			if(!(typeElement?.getAnnotation(Serializable::class.java) != null ||
				KOTLIN_BUILTIN_SERIALIZERS.contains(type.rawType.canonicalName) ||
				(type.isNullable && KOTLIN_BUILTIN_SERIALIZERS.contains(type.rawType.toString().substring(0,type.toString().length-1))))) {
				newType = type.copy(false, type.annotations.plus(contextualSerializationSpec))
			}
		}
		else if (!(typeElement?.getAnnotation(Serializable::class.java) != null ||
				KOTLIN_BUILTIN_SERIALIZERS.contains(type.toString()) ||
					(type.isNullable && KOTLIN_BUILTIN_SERIALIZERS.contains(type.toString().substring(0,type.toString().length-1))))) {
			newType = type.copy(false, type.annotations.plus(contextualSerializationSpec))
		}
		return newType
	}

	private fun generateServerEndpoint(serviceTypeSpec: TypeSpec, servicePackage: String, generatedSourcesRoot: String) {
		val targetPackage = "$servicePackage.kodegen"

		// Create module extension function for Application class
		val moduleFunctionBuilder = FunSpec.builder("${serviceTypeSpec.name}Module")
			.addModifiers(KModifier.PUBLIC)
			.addParameter("service", ClassName(servicePackage, serviceTypeSpec.name!!))
			.addParameter(ParameterSpec.builder("authSchemes", String::class)
				.addModifiers(KModifier.VARARG)
				.build())
			.receiver(Application::class)
			.returns(Unit::class)

		// Initiate routing
		val routingMemberName = MemberName("io.ktor.routing", "routing")
		moduleFunctionBuilder
			.beginControlFlow("%M {", routingMemberName)

		// Iterate through functions in service, generating a route for each
		serviceTypeSpec.funSpecs.forEach { funSpec ->
			val requestObjectName = "${serviceTypeSpec.name!!.capitalize()}${funSpec.name.capitalize()}Request"
			// Create endpoint corresponding to the funSpec
			// 1st step: Route on HTTP Method (POST)
			val post = MemberName("io.ktor.routing", "post")
			val authenticate = MemberName("io.ktor.auth", "authenticate")

			val apiUrl = "/kodegenApi/${serviceTypeSpec.name!!.decapitalize()}/${funSpec.name}"

			if(authenticatedMethods.contains(Pair(serviceTypeSpec.name, funSpec.name))) {
				moduleFunctionBuilder.beginControlFlow("%M(*authSchemes)", authenticate)
			}

			moduleFunctionBuilder
				.beginControlFlow("%M(%S) {", post, apiUrl)

			// If the method takes parameters, attempt to receive the request object
			// 2nd step: Get request object
			val call = MemberName("io.ktor.application", "call")

			if(funSpec.parameters.isNotEmpty()) {
				val requestClassName = ClassName("$servicePackage.kodegen", requestObjectName)
				val receive = MemberName("io.ktor.request", "receive")

				moduleFunctionBuilder
					.addStatement("val request = %M.%M<$requestClassName>()", call, receive)

				// 3rd step: Get all parameters from request object
				funSpec.parameters.forEach { parameter ->
					moduleFunctionBuilder
						.addStatement("val ${parameter.name} = request.${parameter.name}")
				}
			}

			// 4th step: Call the function
			var resultStatement = "service.${funSpec.name}("
			funSpec.parameters.forEachIndexed { index, parameter ->
				resultStatement = "$resultStatement${parameter.name}"
				if (index != funSpec.parameters.lastIndex) {
					resultStatement = "$resultStatement, "
				}
			}
			resultStatement = "$resultStatement)"

			resultStatement = "val result = $resultStatement"

			// 5th step: Wrap the result in a response object and return the result
			val responseObjectName = "${serviceTypeSpec.name!!.capitalize()}${funSpec.name.capitalize()}Response"
			val respond = MemberName("io.ktor.response", "respond")
			val responseStatement = "%M.%M($responseObjectName(result = result))"

			// Add response statements
			moduleFunctionBuilder
				.addStatement(resultStatement)

			if(funSpec.returnType != null) {
				moduleFunctionBuilder
					.addStatement(responseStatement, call, respond)
			}
			else {
				moduleFunctionBuilder
					.addStatement("%M.%M(%M.OK)", call, respond, MemberName("io.ktor.http", "HttpStatusCode"))
			}

			// End the route block
			moduleFunctionBuilder
				.endControlFlow()

			// If used, end the authenticate block
			if(authenticatedMethods.contains(Pair(serviceTypeSpec.name, funSpec.name))) {
				moduleFunctionBuilder.endControlFlow()
			}
		}

		// End routing
		moduleFunctionBuilder
			.endControlFlow()

		// Build the file
		val file = File(generatedSourcesRoot)
		file.mkdir()

		FileSpec.builder(targetPackage, "${serviceTypeSpec.name!!}Module")
			.addComment(AUTOGENERATED_COMMENT)
			.addFunction(moduleFunctionBuilder.build())
			.build()
			.writeTo(file)
	}

	private fun generateServiceInvoker(serviceTypeSpec: TypeSpec, servicePackage: String, generatedSourcesRoot: String) {
		val targetPackage = "$servicePackage.kodegen"

		// Prepare FileSpec builder
		val fileSpecBuilder = FileSpec.builder(targetPackage, "${serviceTypeSpec.name!!}Client")
			.addComment(AUTOGENERATED_COMMENT)

		// Generate Client
		// Define name and package of generated class
		val className = ClassName(targetPackage, "${serviceTypeSpec.name!!}Client")

		// Create class builder and set data modifier
		val classBuilder = TypeSpec.classBuilder(className)
			.primaryConstructor(FunSpec.constructorBuilder()
				.addParameter(ParameterSpec.builder("client", HttpClient::class)
					.build())
				.addParameter(ParameterSpec.builder("baseUrl", String::class)
					.build())
				.build())
			.addProperty(PropertySpec.builder("client", HttpClient::class)
				.initializer("client")
				.build())
			.addProperty(PropertySpec.builder("baseUrl", String::class)
				.initializer("baseUrl")
				.build())

		// Iterate through functions, adding a client function for each
		serviceTypeSpec.funSpecs.forEach { funSpec ->
			val apiUrl = "\$baseUrl/kodegenApi/${serviceTypeSpec.name!!.decapitalize()}/${funSpec.name}"
			val requestObjectName = "${serviceTypeSpec.name!!.capitalize()}${funSpec.name.capitalize()}Request"
			val responseObjectName = "${serviceTypeSpec.name!!.capitalize()}${funSpec.name.capitalize()}Response"

			// Create client function
			val clientFunctionBuilder = FunSpec.builder(funSpec.name)
				.addModifiers(KModifier.PUBLIC)

			if(funSpec.returnType != null)
			{
				clientFunctionBuilder
					.returns(funSpec.returnType!!)
			}

			// If service interface declares the function suspendable, add suspend modifier
			if (funSpec.modifiers.contains(KModifier.SUSPEND)) {
				clientFunctionBuilder.addModifiers(KModifier.SUSPEND)
			}


			// For each parameter of the function, add them to the client function
			funSpec.parameters.forEach { parameter ->
				clientFunctionBuilder.addParameter(parameter)
			}

			// 2nd step: Create the body
			if(funSpec.parameters.isNotEmpty()) {
				var bodyStatement = "$requestObjectName("

				funSpec.parameters.forEachIndexed { index, parameter ->
					bodyStatement = "$bodyStatement${parameter.name} = ${parameter.name}"
					if(index != funSpec.parameters.lastIndex) {
						bodyStatement = "$bodyStatement, "
					}
				}
				bodyStatement = "$bodyStatement)"

				clientFunctionBuilder
					.addStatement("val messageBody = $bodyStatement")
			}

			// 3rd step: Make request
			val post = MemberName("io.ktor.client.request", "post")
			val url = MemberName("io.ktor.client.request", "url")
			val contentType = MemberName("io.ktor.http", "contentType")
			val contentTypeEnum = MemberName("io.ktor.http", "ContentType")


			// If the function is not suspendable, wrap the network call in runBlocking
			// and set response to the result of this
			if (!funSpec.modifiers.contains(KModifier.SUSPEND)) {
				val runBlocking = MemberName("kotlinx.coroutines", "runBlocking")
				clientFunctionBuilder
					.beginControlFlow("val response = %M", runBlocking)
			}

			// If the function is suspendable, set response to the result of the post call
			var responseStatement = if (funSpec.returnType == null) "client.%M<Unit>" else "client.%M<$responseObjectName>"

			if (funSpec.modifiers.contains((KModifier.SUSPEND))) {
				responseStatement = "val response = $responseStatement"
			}

			// Set the URL and ContentType
			clientFunctionBuilder
				.beginControlFlow(responseStatement, post)
				.addStatement("%M(%P)", url, apiUrl)
				.addStatement("%M(%M.Application.Json)", contentType, contentTypeEnum)

			// If the function call has any parameters, serialize a request object and add it
			if (funSpec.parameters.isNotEmpty()) {
				clientFunctionBuilder.addStatement("body = messageBody")
			}

			// End the post block
			clientFunctionBuilder
				.endControlFlow()

			// If the function is not suspendable, end the runBlocking block
			if (!funSpec.modifiers.contains(KModifier.SUSPEND)) {
				clientFunctionBuilder
					.endControlFlow()
			}

			// 4th: step: Parse the result and return it
			if(funSpec.returnType != null) {
				clientFunctionBuilder
					.addStatement("return response.result")
			}
			else {
				clientFunctionBuilder.addStatement("return")
			}


			// 5th step: Add function to FileSpec builder
			classBuilder
				.addFunction(clientFunctionBuilder.build())
		}

		// Build the file
		val file = File(generatedSourcesRoot)
		file.mkdir()

		// Because of prototyping constraints, serialization builtins are imported directly
		// Rather than through the use of MemberNames
		fileSpecBuilder
			.addType(classBuilder.build())
			.build()
			.writeTo(file)
	}
}