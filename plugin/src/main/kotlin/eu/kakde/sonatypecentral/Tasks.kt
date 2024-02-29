package eu.kakde.sonatypecentral

import com.google.gson.GsonBuilder
import eu.kakde.sonatypecentral.utils.ENDPOINT
import eu.kakde.sonatypecentral.utils.HashComputation
import eu.kakde.sonatypecentral.utils.IOUtils.createDirectoryStructure
import eu.kakde.sonatypecentral.utils.IOUtils.renameFile
import eu.kakde.sonatypecentral.utils.ZipUtils
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import okio.IOException
import org.gradle.api.DefaultTask
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.plugins.signing.SigningExtension
import java.io.File
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.Locale
import javax.inject.Inject

abstract class GenerateMavenArtifacts
    @Inject
    constructor(
        @Internal val componentType: String,
    ) : DefaultTask() {
        init {
            val commonDependencies =
                arrayOf(
                    "clean",
                    "javadocJar",
                    "sourcesJar",
                    "generatePomFileForMavenPublication",
                    "generateMetadataFileForMavenPublication",
                )

            val additionalDependencies =
                when {
                    componentType == "versionCatalog" -> emptyArray()
                    project.hasProperty("bootJar") -> arrayOf("bootJar")
                    else -> arrayOf("jar")
                }

            val dependencies = additionalDependencies + commonDependencies

            this.dependsOn(*dependencies)

            group = CUSTOM_TASK_GROUP
            description = "Generates all necessary artifacts for maven publication."
        }

        @TaskAction
        fun action() {
            println("Executing 'generateMavenArtifacts' Task...")
        }
    }

abstract class SignMavenArtifact
    @Inject
    constructor(
        @Internal val mavenPublication: MavenPublication,
    ) : DefaultTask() {
        init {
            this.dependsOn("generateMavenArtifacts")

            group = CUSTOM_TASK_GROUP
            description = "Sign necessary artifacts generated by 'generateMavenArtifacts'."
        }

        private val signingExtension: SigningExtension = project.extensions.getByType(SigningExtension::class.java)

        @TaskAction
        fun action() {
            println("Executing 'signMavenArtifact' Task...")
            val internalPublication = mavenPublication as MavenPublicationInternal
            internalPublication.publishableArtifacts.forEach {
                signingExtension.sign(it.file)
            }
        }
    }

abstract class AggregateFiles : DefaultTask() {
    init {
        this.dependsOn("signMavenArtifacts")

        group = CUSTOM_TASK_GROUP
        description = "Aggregate all files to a temporary directory."
    }

    @Internal
    var directoryPath: String = ""

    @Internal
    var groupId: String = project.group.toString()

    @Internal
    var artifactId: String = project.name

    @Internal
    var version: String = project.version.toString()

    @TaskAction
    fun action() {
        println("Executing AggregateFiles")

        val filesToAggregate = mutableListOf<File>()
        val buildDirectory = project.layout.buildDirectory

        // Add all files from the libs directory
        val libsDir = buildDirectory.dir("libs").get().asFileTree
        // Todo: improve this code. Remove generation of plain jar file in publication.
        val filesToAdd =
            libsDir.files.filter { file ->
                !file.name.endsWith("-plain.jar") && !file.name.endsWith("-plain.jar.asc")
            }
        filesToAggregate.addAll(filesToAdd)

        // Rename and Add all files from the publications/maven directory, e.g. pom-default.xml and pom-default.xml.asc
        val mavenPublicationsDir = buildDirectory.dir("publications/maven").orNull
        mavenPublicationsDir?.asFileTree?.forEach { file: File ->
            val newName =
                when {
                    file.name == "pom-default.xml" -> "$artifactId-$version.pom"
                    file.name == "pom-default.xml.asc" -> "$artifactId-$version.pom.asc"
                    file.name == "module.json" -> "$artifactId-$version.module"
                    file.name == "module.json.asc" -> "$artifactId-$version.module.asc"
                    else -> "$artifactId-$version.${file.name}"
                }
            filesToAggregate.addLast(renameFile(file, newName))
        }

        val versionCatalogDir = buildDirectory.dir("version-catalog").orNull
        versionCatalogDir?.asFileTree?.forEach { file ->

            val fileName = file.name
            val newName =
                when {
                    fileName.endsWith("versions.toml") -> "$artifactId-$version.toml"
                    fileName.endsWith("versions.toml.asc") -> "$artifactId-$version.toml.asc"
                    else -> fileName
                }

            filesToAggregate.addLast(renameFile(file, newName))
        }

        val tempDirFile = createDirectoryStructure(directoryPath)
        filesToAggregate.forEach { file ->
            tempDirFile.let {
                file.copyTo(it.resolve(file.name), overwrite = true)
            }
        }
    }
}

abstract class ComputeHash
    @Inject
    constructor(
        @Internal val directory: File,
        @Internal val shaAlgorithms: List<String>,
    ) : DefaultTask() {
        init {
            group = CUSTOM_TASK_GROUP
            description = "Compute Hash of all files in a temporary directory."
            this.dependsOn("aggregateFiles")
        }

        @TaskAction
        fun run() {
            println("Executing 'computeHash' Task...")
            println("Sha algorithms used: $shaAlgorithms")
            HashComputation.computeAndSaveDirectoryHashes(directory, shaAlgorithms)
        }
    }

abstract class CreateZip : DefaultTask() {
    init {
        group = CUSTOM_TASK_GROUP
        description = "Create a zip file comprising all files located within a temporary directory."
        this.dependsOn("computeHash")
    }

    // Folder path to be archived
    @Internal
    var folderPath: String? = ""

    @TaskAction
    fun createArchive() {
        println("Executing 'createZip' task...")
        println("Creating zip file from the folder: $folderPath ")
        folderPath?.let {
            ZipUtils.prepareZipFile(
                it,
                project.layout.buildDirectory.get().asFile.resolve("upload.zip").path,
            )
        }
    }
}

abstract class PublishToSonatypeCentral : DefaultTask() {
    init {
        group = CUSTOM_TASK_GROUP
        description = "Publish to New Sonatype Maven Central Repository."
        this.dependsOn("createZip")
    }

    private val extension = project.extensions.getByType(SonatypeCentralPublishExtension::class.java)
    private val zipFileProvider = project.layout.buildDirectory.file("upload.zip")

    @Throws(IOException::class, URISyntaxException::class)
    @TaskAction
    fun uploadZip() {
        println("Executing 'publishToSonatypeCentral' tasks...")
        val username = extension.username.get()
        val password = extension.password.get()
        require(username.isNotBlank()) { "Sonatype username must not be empty" }
        require(password.isNotBlank()) { "Sonatype password must not be empty" }

        val groupId = extension.groupId.get()
        val artifactId = extension.artifactId.get()
        val version = extension.version.get()
        val publishingType = extension.publishingType.get().uppercase(Locale.getDefault()) // AUTOMATIC
        val name = URLEncoder.encode("$groupId:$artifactId:$version", UTF_8)

        val credentials = "$username:$password"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())
        val url = "${ENDPOINT.UPLOAD}?publishingType=$publishingType&name=$name"

        val body =
            MultipartBody.Builder()
                .addFormDataPart(
                    "bundle",
                    "upload.zip",
                    zipFileProvider.get().asFile.asRequestBody("application/zip".toMediaType()),
                )
                .build()

        val request =
            Request.Builder()
                .post(body)
                .addHeader("Authorization", "UserToken $encodedCredentials")
                .url(url)
                .build()

        val client = createHttpClient()
        val response = client.newCall(request).execute()
        handleResponse(
            response,
            successMessage = "Published to Maven central. Deployment ID:",
            failureMessage = "Cannot publish to Maven Central.",
        )
    }
}

abstract class GetDeploymentStatus : DefaultTask() {
    init {
        group = CUSTOM_TASK_GROUP
        description = "Get deployment status using deploymentId"
    }

    @Input
    var deploymentId: String = project.findProperty("deploymentId")?.toString() ?: ""

    private val extension = project.extensions.getByType(SonatypeCentralPublishExtension::class.java)

    @TaskAction
    fun executeTask() {
        println("Executing 'getDeploymentStatus' task... With parameter deploymentId=$deploymentId")
        val username = extension.username.get()
        val password = extension.password.get()
        require(username.isNotBlank()) { "Sonatype username must not be empty" }
        require(password.isNotBlank()) { "Sonatype password must not be empty" }
        val credentials = "$username:$password"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())

        val requestBody = "".toRequestBody("application/json".toMediaType())

        val request =
            Request.Builder()
                .post(requestBody)
                .addHeader("Authorization", "UserToken $encodedCredentials")
                .url("${ENDPOINT.STATUS}?id=$deploymentId")
                .build()

        val client = createHttpClient()
        val response = client.newCall(request).execute()
        handleResponse(
            response,
            successMessage = "Deployment Status:",
            failureMessage = "Failed to get deployment status.",
        )
    }
}

abstract class DropDeployment : DefaultTask() {
    init {
        group = CUSTOM_TASK_GROUP
        description = "Drop deployment using deploymentId."
    }

    @Input
    var deploymentId: String = project.findProperty("deploymentId")?.toString() ?: ""

    private val extension = project.extensions.getByType(SonatypeCentralPublishExtension::class.java)

    @TaskAction
    fun executeTask() {
        println("Executing 'dropDeployment' task... Dropping deployment for deploymentId=$deploymentId")
        val username = extension.username.get()
        val password = extension.password.get()
        require(username.isNotBlank()) { "Sonatype username must not be empty" }
        require(password.isNotBlank()) { "Sonatype password must not be empty" }
        val credentials = "$username:$password"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray())

        val request =
            Request.Builder()
                .delete()
                .addHeader("Authorization", "UserToken $encodedCredentials")
                .url("${ENDPOINT.DEPLOYMENT}/$deploymentId")
                .build()

        val client = createHttpClient()
        val response = client.newCall(request).execute()
        handleResponse(response, "Deployment Dropped Successfully for Deployment ID: $deploymentId", "Failed to drop the deployment.")
    }
}

private fun createHttpClient(): OkHttpClient {
    return OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.HEADERS
            },
        )
        .build()
}

private fun handleResponse(
    response: Response,
    successMessage: String,
    failureMessage: String,
) {
    val responseBody = response.body?.string()
    val gson = GsonBuilder().setPrettyPrinting().create()
    if (!response.isSuccessful) {
        val statusCode = response.code
        val errorMessage = gson.fromJson(responseBody, ErrorMessage::class.java) ?: ErrorMessage(Error("Unknown Error: $responseBody"))
        println("$failureMessage\nHTTP Status Code: $statusCode\nError Message: ${errorMessage.error.message}")
    } else {
        val jsonObject = gson.fromJson(responseBody, Any::class.java)
        val prettyJsonString = gson.toJson(jsonObject)
        println("$successMessage\n$prettyJsonString")
    }
}

data class ErrorMessage(val error: Error)

data class Error(val message: String)
