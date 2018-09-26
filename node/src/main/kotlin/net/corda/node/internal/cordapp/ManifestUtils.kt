package net.corda.node.internal.cordapp

import java.util.jar.Attributes
import java.util.jar.Manifest

fun createTestManifest(name: String, title: String, version: String, vendor: String, targetVersion: Int): Manifest {
    val manifest = Manifest()

    // Mandatory manifest attribute. If not present, all other entries are silently skipped.
    manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"

    manifest["Name"] = name

    manifest["Specification-Title"] = title
    manifest["Specification-Version"] = version
    manifest["Specification-Vendor"] = vendor

    manifest["Implementation-Title"] = title
    manifest["Implementation-Version"] = version
    manifest["Implementation-Vendor"] = vendor
    manifest["Target-Platform-Version"] = targetVersion.toString()

    return manifest
}

operator fun Manifest.set(key: String, value: String): String? {
    return mainAttributes.putValue(key, value)
}

operator fun Manifest.get(key: String): String? = mainAttributes.getValue(key)
