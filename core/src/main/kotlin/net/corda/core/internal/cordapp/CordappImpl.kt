package net.corda.core.internal.cordapp

import net.corda.core.DeleteForDJVM
import net.corda.core.cordapp.Cordapp
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.internal.toPath
import net.corda.core.schemas.MappedSchema
import net.corda.core.serialization.SerializationCustomSerializer
import net.corda.core.serialization.SerializationWhitelist
import net.corda.core.serialization.SerializeAsToken
import java.net.URL

@DeleteForDJVM
data class CordappImpl(
        override val contractClassNames: List<String>,
        override val initiatedFlows: List<Class<out FlowLogic<*>>>,
        override val rpcFlows: List<Class<out FlowLogic<*>>>,
        override val serviceFlows: List<Class<out FlowLogic<*>>>,
        override val schedulableFlows: List<Class<out FlowLogic<*>>>,
        override val services: List<Class<out SerializeAsToken>>,
        override val serializationWhitelists: List<SerializationWhitelist>,
        override val serializationCustomSerializers: List<SerializationCustomSerializer<*, *>>,
        override val customSchemas: Set<MappedSchema>,
        override val allFlows: List<Class<out FlowLogic<*>>>,
        override val jarPath: URL,
        override val jarHash: SecureHash.SHA256,
        override val shortName: String,
        override val vendor: String,
        override val version: String,
        override val minimumPlatformVersion: Int,
        override val targetPlatformVersion: Int) : Cordapp {
    override val name: String = jarName(jarPath)

    companion object {
        const val UNKNOWN_VALUE = "Unknown"
        fun jarName(url: URL): String = url.toPath().fileName.toString().removeSuffix(".jar")
    }

    /**
     * An exhaustive list of all classes relevant to the node within this CorDapp*
     */
    // TODO: Also add [SchedulableFlow] as a Cordapp class
    override val cordappClasses: List<String> = (rpcFlows + initiatedFlows + services + serializationWhitelists.map { javaClass }).map { it.name } + contractClassNames

    fun hasUnknownFields(): Boolean = arrayOf(shortName, vendor, version).any { it == UNKNOWN_VALUE }
}
