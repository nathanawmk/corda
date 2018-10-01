package net.corda.node.internal.cordapp

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import io.github.lukehutch.fastclasspathscanner.scanner.ScanResult
import net.corda.core.cordapp.Cordapp
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.sha256
import net.corda.core.flows.*
import net.corda.core.internal.*
import net.corda.core.internal.cordapp.CordappImpl
import net.corda.core.internal.cordapp.CordappInfoResolver
import net.corda.core.node.services.CordaService
import net.corda.core.schemas.MappedSchema
import net.corda.core.serialization.SerializationCustomSerializer
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.utilities.contextLogger
import net.corda.node.VersionInfo
import net.corda.node.cordapp.CordappLoader
import net.corda.node.internal.classloading.requireAnnotation
import net.corda.nodeapi.internal.coreContractClasses
import net.corda.serialization.internal.DefaultWhitelist
import org.apache.commons.collections4.map.LRUMap
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.*
import java.util.jar.JarInputStream
import java.util.jar.Manifest
import kotlin.reflect.KClass
import kotlin.streams.toList

/**
 * Handles CorDapp loading and classpath scanning of CorDapp JARs
 *
 * @property cordappJarPaths The classpath of cordapp JARs
 */
class JarScanningCordappLoader private constructor(private val cordappJarPaths: List<RestrictedURL>,
                                                   private val versionInfo: VersionInfo = VersionInfo.UNKNOWN) : CordappLoaderTemplate() {

    override val cordapps: List<CordappImpl> by lazy { loadCordapps() + coreCordapp }

    override val appClassLoader: ClassLoader = URLClassLoader(cordappJarPaths.stream().map { it.url }.toTypedArray(), javaClass.classLoader)

    init {
        if (cordappJarPaths.isEmpty()) {
            logger.info("No CorDapp paths provided")
        } else {
            logger.info("Loading CorDapps from ${cordappJarPaths.joinToString()}")
        }
    }

    companion object {
        private val logger = contextLogger()

        /**
         * Creates a CordappLoader from multiple directories.
         *
         * @param corDappDirectories Directories used to scan for CorDapp JARs.
         */
        fun fromDirectories(corDappDirectories: Iterable<Path>, versionInfo: VersionInfo = VersionInfo.UNKNOWN): JarScanningCordappLoader {
            logger.info("Looking for CorDapps in ${corDappDirectories.distinct().joinToString(", ", "[", "]")}")
            return JarScanningCordappLoader(corDappDirectories.distinct().flatMap(this::jarUrlsInDirectory).map { it.restricted() }, versionInfo)
        }

        /**
         * Creates a CordappLoader loader out of a list of JAR URLs.
         *
         * @param scanJars Uses the JAR URLs provided for classpath scanning and Cordapp detection.
         */
        fun fromJarUrls(scanJars: List<URL>, versionInfo: VersionInfo = VersionInfo.UNKNOWN): JarScanningCordappLoader {
            return JarScanningCordappLoader(scanJars.map { it.restricted() }, versionInfo)
        }

        private fun URL.restricted(rootPackageName: String? = null) =  RestrictedURL(this, rootPackageName)

        private fun jarUrlsInDirectory(directory: Path): List<URL> {

            return if (!directory.exists()) {
                emptyList()
            } else {
                directory.list { paths ->
                    // `toFile()` can't be used here...
                    paths.filter { it.toString().endsWith(".jar") }.map { it.toUri().toURL() }.toList()
                }
            }
        }

        /** A list of the core RPC flows present in Corda */
        private val coreRPCFlows = listOf(
                ContractUpgradeFlow.Initiate::class.java,
                ContractUpgradeFlow.Authorise::class.java,
                ContractUpgradeFlow.Deauthorise::class.java)
    }

    /** A Cordapp representing the core package which is not scanned automatically. */
    @VisibleForTesting
    internal val coreCordapp = CordappImpl(
            contractClassNames = listOf(),
            initiatedFlows = listOf(),
            rpcFlows = coreRPCFlows,
            serviceFlows = listOf(),
            schedulableFlows = listOf(),
            services = listOf(),
            serializationWhitelists = listOf(),
            serializationCustomSerializers = listOf(),
            customSchemas = setOf(),
            allFlows = listOf(),
            jarPath = ContractUpgradeFlow.javaClass.location, // Core JAR location
            jarHash = SecureHash.allOnesHash,
            shortName = "corda-core",
            vendor = versionInfo.vendor,
            version = versionInfo.releaseVersion,
            minimumPlatformVersion = 1,
            targetPlatformVersion = versionInfo.platformVersion
    )

    private fun loadCordapps(): List<CordappImpl> {
        val cordapps = cordappJarPaths.map { scanCordapp(it).toCordapp(it) }
                .filter {
                    if (it.minimumPlatformVersion > versionInfo.platformVersion) {
                        logger.warn("Not loading CorDapp ${it.shortName} (${it.vendor}) as it requires minimum platform " +
                                "version ${it.minimumPlatformVersion} (This node is running version ${versionInfo.platformVersion}).")
                        false
                    } else {
                        true
                    }
                }
        cordapps.forEach(CordappInfoResolver::register)
        return cordapps
    }

    private fun RestrictedScanResult.toCordapp(url: RestrictedURL): CordappImpl {
        val manifest: Manifest? = url.url.openStream().let(::JarInputStream).use { it.manifest }
        val defaultShortName = CordappImpl.jarName(url.url)
        val shortName = manifest?.get("Name") ?: defaultShortName
        val vendor = manifest?.get("Implementation-Vendor") ?: CordappImpl.UNKNOWN_VALUE
        val version = manifest?.get("Implementation-Version") ?: CordappImpl.UNKNOWN_VALUE
        val minPlatformVersion = manifest?.get("Min-Platform-Version")?.toIntOrNull() ?: 1
        val targetPlatformVersion = manifest?.get("Target-Platform-Version")?.toIntOrNull() ?: minPlatformVersion
        return CordappImpl(
                contractClassNames = findContractClassNames(this),
                initiatedFlows = findInitiatedFlows(this),
                rpcFlows = findRPCFlows(this),
                serviceFlows = findServiceFlows(this),
                schedulableFlows = findSchedulableFlows(this),
                services = findServices(this),
                serializationWhitelists = findPlugins(url),
                serializationCustomSerializers = findSerializers(this),
                customSchemas = findCustomSchemas(this),
                allFlows = findAllFlows(this),
                jarPath = url.url,
                jarHash = getJarHash(url.url),
                shortName = shortName,
                vendor = vendor,
                version = version,
                minimumPlatformVersion = minPlatformVersion,
                targetPlatformVersion = targetPlatformVersion
        )
    }

    private fun getJarHash(url: URL): SecureHash.SHA256 = url.openStream().readFully().sha256()

    private fun findServices(scanResult: RestrictedScanResult): List<Class<out SerializeAsToken>> {
        return scanResult.getClassesWithAnnotation(SerializeAsToken::class, CordaService::class)
    }

    private fun findInitiatedFlows(scanResult: RestrictedScanResult): List<Class<out FlowLogic<*>>> {
        return scanResult.getClassesWithAnnotation(FlowLogic::class, InitiatedBy::class)
                // First group by the initiating flow class in case there are multiple mappings
                .groupBy { it.requireAnnotation<InitiatedBy>().value.java }
                .map { (initiatingFlow, initiatedFlows) ->
                    val sorted = initiatedFlows.sortedWith(FlowTypeHierarchyComparator(initiatingFlow))
                    if (sorted.size > 1) {
                        logger.warn("${initiatingFlow.name} has been specified as the inititating flow by multiple flows " +
                                "in the same type hierarchy: ${sorted.joinToString { it.name }}. Choosing the most " +
                                "specific sub-type for registration: ${sorted[0].name}.")
                    }
                    sorted[0]
                }
    }

    private fun Class<out FlowLogic<*>>.isUserInvokable(): Boolean {
        return Modifier.isPublic(modifiers) && !isLocalClass && !isAnonymousClass && (!isMemberClass || Modifier.isStatic(modifiers))
    }

    private fun findRPCFlows(scanResult: RestrictedScanResult): List<Class<out FlowLogic<*>>> {
        return scanResult.getClassesWithAnnotation(FlowLogic::class, StartableByRPC::class).filter { it.isUserInvokable() }
    }

    private fun findServiceFlows(scanResult: RestrictedScanResult): List<Class<out FlowLogic<*>>> {
        return scanResult.getClassesWithAnnotation(FlowLogic::class, StartableByService::class)
    }

    private fun findSchedulableFlows(scanResult: RestrictedScanResult): List<Class<out FlowLogic<*>>> {
        return scanResult.getClassesWithAnnotation(FlowLogic::class, SchedulableFlow::class)
    }

    private fun findAllFlows(scanResult: RestrictedScanResult): List<Class<out FlowLogic<*>>> {
        return scanResult.getConcreteClassesOfType(FlowLogic::class)
    }

    private fun findContractClassNames(scanResult: RestrictedScanResult): List<String> {
        return coreContractClasses.flatMap { scanResult.getNamesOfClassesImplementing(it) }.distinct()
    }

    private fun findPlugins(cordappJarPath: RestrictedURL): List<SerializationWhitelist> {
        return ServiceLoader.load(SerializationWhitelist::class.java, URLClassLoader(arrayOf(cordappJarPath.url), appClassLoader)).toList().filter {
            it.javaClass.location == cordappJarPath.url && it.javaClass.name.startsWith(cordappJarPath.qualifiedNamePrefix)
        } + DefaultWhitelist // Always add the DefaultWhitelist to the whitelist for an app.
    }

    private fun findSerializers(scanResult: RestrictedScanResult): List<SerializationCustomSerializer<*, *>> {
        return scanResult.getClassesImplementing(SerializationCustomSerializer::class)
    }

    private fun findCustomSchemas(scanResult: RestrictedScanResult): Set<MappedSchema> {
        return scanResult.getClassesWithSuperclass(MappedSchema::class).toSet()
    }

    private val cachedScanResult = LRUMap<RestrictedURL, RestrictedScanResult>(1000)

    private fun scanCordapp(cordappJarPath: RestrictedURL): RestrictedScanResult {
        logger.info("Scanning CorDapp in ${cordappJarPath.url}")
        return cachedScanResult.computeIfAbsent(cordappJarPath) {
            RestrictedScanResult(FastClasspathScanner().addClassLoader(appClassLoader).overrideClasspath(cordappJarPath.url).scan(), cordappJarPath.qualifiedNamePrefix)
        }
    }

    private class FlowTypeHierarchyComparator(val initiatingFlow: Class<out FlowLogic<*>>) : Comparator<Class<out FlowLogic<*>>> {
        override fun compare(o1: Class<out FlowLogic<*>>, o2: Class<out FlowLogic<*>>): Int {
            return when {
                o1 == o2 -> 0
                o1.isAssignableFrom(o2) -> 1
                o2.isAssignableFrom(o1) -> -1
                else -> throw IllegalArgumentException("${initiatingFlow.name} has been specified as the initiating flow by " +
                        "both ${o1.name} and ${o2.name}")
            }
        }
    }

    private fun <T : Any> loadClass(className: String, type: KClass<T>): Class<out T>? {
        return try {
            appClassLoader.loadClass(className).asSubclass(type.java)
        } catch (e: ClassCastException) {
            logger.warn("As $className must be a sub-type of ${type.java.name}")
            null
        } catch (e: Exception) {
            logger.warn("Unable to load class $className", e)
            null
        }
    }

    /** @property rootPackageName only this package and subpackages may be extracted from [url], or null to allow all packages. */
    private data class RestrictedURL(val url: URL, val rootPackageName: String?) {
        val qualifiedNamePrefix: String get() = rootPackageName?.let { "$it." } ?: ""
    }

    private inner class RestrictedScanResult(private val scanResult: ScanResult, private val qualifiedNamePrefix: String) {
        fun getNamesOfClassesImplementing(type: KClass<*>): List<String> {
            return scanResult.getNamesOfClassesImplementing(type.java)
                    .filter { it.startsWith(qualifiedNamePrefix) }
        }

        fun <T : Any> getClassesWithSuperclass(type: KClass<T>): List<T> {
            return scanResult.getNamesOfSubclassesOf(type.java)
                    .filter { it.startsWith(qualifiedNamePrefix) }
                    .mapNotNull { loadClass(it, type) }
                    .filterNot { Modifier.isAbstract(it.modifiers) }
                    .map { it.kotlin.objectOrNewInstance() }
        }

        fun <T : Any> getClassesImplementing(type: KClass<T>): List<T> {
            return scanResult.getNamesOfClassesImplementing(type.java)
                    .filter { it.startsWith(qualifiedNamePrefix) }
                    .mapNotNull { loadClass(it, type) }
                    .filterNot { Modifier.isAbstract(it.modifiers) }
                    .map { it.kotlin.objectOrNewInstance() }
        }

        fun <T : Any> getClassesWithAnnotation(type: KClass<T>, annotation: KClass<out Annotation>): List<Class<out T>> {
            return scanResult.getNamesOfClassesWithAnnotation(annotation.java)
                    .filter { it.startsWith(qualifiedNamePrefix) }
                    .mapNotNull { loadClass(it, type) }
                    .filterNot { Modifier.isAbstract(it.modifiers) }
        }

        fun <T : Any> getConcreteClassesOfType(type: KClass<T>): List<Class<out T>> {
            return scanResult.getNamesOfSubclassesOf(type.java)
                    .filter { it.startsWith(qualifiedNamePrefix) }
                    .mapNotNull { loadClass(it, type) }
                    .filterNot { Modifier.isAbstract(it.modifiers) }
        }
    }
}

/**
 * Thrown when scanning CorDapps.
 */
class MultipleCordappsForFlowException(message: String) : Exception(message)

abstract class CordappLoaderTemplate : CordappLoader {
    override val flowCordappMap: Map<Class<out FlowLogic<*>>, Cordapp> by lazy {
        cordapps.flatMap { corDapp -> corDapp.allFlows.map { flow -> flow to corDapp } }
                .groupBy { it.first }
                .mapValues { entry ->
                    if (entry.value.size > 1) {
                        throw MultipleCordappsForFlowException("There are multiple CorDapp JARs on the classpath for flow " +
                                "${entry.value.first().first.name}: [ ${entry.value.joinToString { it.second.name }} ].")
                    }
                    entry.value.single().second
                }
    }

    override val cordappSchemas: Set<MappedSchema> by lazy {
        cordapps.flatMap { it.customSchemas }.toSet()
    }

    override val appClassLoader: ClassLoader by lazy {
        URLClassLoader(cordapps.stream().map { it.jarPath }.toTypedArray(), javaClass.classLoader)
    }
}
