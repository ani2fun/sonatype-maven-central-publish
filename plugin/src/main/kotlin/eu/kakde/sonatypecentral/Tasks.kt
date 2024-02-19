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

abstract class SignMavenArtifact
    @Inject
    constructor(
        componentType: String,
        @Input val mavenPublication: MavenPublication,
    ) : DefaultTask() {
        init {
            val commonDependencies =
                arrayOf(
                    "javadocJar",
                    "sourcesJar",
                    "generatePomFileForMavenPublication",
                    "generateMetadataFileForMavenPublication",
                )

            val dependencies =
                if (componentType == "versionCatalog") {
                    commonDependencies
                } else {
                    arrayOf("jar") + commonDependencies
                }

            this.dependsOn(*dependencies)

            group = CUSTOM_TASK_GROUP
            description = "Sign all necessary files/artifacts."
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
        group = CUSTOM_TASK_GROUP
        description = "AggregateFilesTask will create a required temporary directory and copy all signed files to it."
        this.dependsOn("signMavenArtifact")
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
        filesToAggregate.addAll(buildDirectory.dir("libs").get().asFileTree.files)

        // Rename and Add all files from the publications/maven directory, e.g. pom-default.xml and pom-default.xml.asc
        val mavenPublicationsDir = buildDirectory.dir("publications/maven").orNull
        mavenPublicationsDir?.asFileTree?.forEach { file: File ->
            val newName =
                when (file.name) {
                    "pom-default.xml" -> "$artifactId-$version.pom"
                    "pom-default.xml.asc" -> "$artifactId-$version.pom.asc"
                    "module.json" -> "$artifactId-$version.module.json"
                    "module.json.asc" -> "$artifactId-$version.module.json.asc"
                    else -> "$artifactId-$version.${file.name}"
                }
            filesToAggregate.addLast(renameFile(file, newName))
        }

        val versionCatalogDir = buildDirectory.dir("version-catalog").orNull
        versionCatalogDir?.asFileTree?.forEach { file ->

            val fileName = file.name
            val newName =
                when {
                    fileName.endsWith("versions.toml") -> "$artifactId-$version.versions.toml"
                    fileName.endsWith("versions.toml.asc") -> "$artifactId-$version.versions.toml.asc"
                    else -> fileName
                }

            filesToAggregate.addLast(renameFile(file, newName))
        }

        val tempDirFile = createDirectoryStructure(directoryPath)
        filesToAggregate.forEach { file ->
            println(file.name)
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
    ) : DefaultTask() {
        init {
            group = CUSTOM_TASK_GROUP
            description = "Compute Hash of all files in a directory"
            this.dependsOn("aggregateFiles")
        }

        @TaskAction
        fun run() {
            println("Executing 'computeHash' Task...")
            HashComputation.computeAndSaveDirectoryHashes(directory)
        }
    }

abstract class CreateZip : DefaultTask() {
    init {
        group = CUSTOM_TASK_GROUP
        description = "Create a zip of all files in a directory"
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
        description = "Publish to Sonatype Central Repository"
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

        val client =
            OkHttpClient.Builder()
                .addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                    },
                )
                .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
        if (!response.isSuccessful) {
            println("Cannot publish to Maven Central (status='${response.code}'). Deployment ID='$responseBody'")
        } else {
            val gson = GsonBuilder().setPrettyPrinting().create()
            val jsonResponse = gson.toJson(responseBody)
            println("Deployment Response: $jsonResponse")
        }
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

        val client =
            OkHttpClient.Builder()
                .addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                    },
                )
                .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (!response.isSuccessful) {
            println("Failed to get deployment status (status='${response.code}'). Response: $responseBody")
        } else {
            println("Deployment Response:\n$responseBody")
        }
    }
}

abstract class DropDeployment : DefaultTask() {
    init {
        group = CUSTOM_TASK_GROUP
        description = "Drop deployment using deploymentId"
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

        val client =
            OkHttpClient.Builder()
                .addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                    },
                )
                .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()

        if (!response.isSuccessful) {
            println("Failed to get deployment status (status='${response.code}'). Response: $responseBody")
        } else {
            println("Deployment Dropped Successfully for deploymentId=$deploymentId")
        }
    }
}
