package net.corda.testing.internal

import net.corda.core.contracts.ContractClassName
import net.corda.core.cordapp.Cordapp
import net.corda.core.internal.DEPLOYED_CORDAPP_UPLOADER
import net.corda.core.node.services.AttachmentId
import net.corda.core.node.services.AttachmentStorage
import net.corda.node.cordapp.CordappLoader
import net.corda.node.internal.cordapp.CordappProviderImpl
import net.corda.testing.services.MockAttachmentStorage
import java.util.*

class MockCordappProvider(
        cordappLoader: CordappLoader,
        attachmentStorage: AttachmentStorage,
        cordappConfigProvider: MockCordappConfigProvider = MockCordappConfigProvider()
) : CordappProviderImpl(cordappLoader, cordappConfigProvider, attachmentStorage) {

    private val cordappRegistry = mutableListOf<Pair<Cordapp, AttachmentId>>()

    fun addMockCordapp(contractClassName: ContractClassName, attachments: MockAttachmentStorage) {
        val cordapp = emptyCordappImpl().copy(contractClassNames = listOf(contractClassName))
        if (cordappRegistry.none { it.first.contractClassNames.contains(contractClassName) }) {
            cordappRegistry.add(Pair(cordapp, findOrImportAttachment(listOf(contractClassName), contractClassName.toByteArray(), attachments)))
        }
    }

    override fun getContractAttachmentID(contractClassName: ContractClassName): AttachmentId? {
        return cordappRegistry.find { it.first.contractClassNames.contains(contractClassName) }?.second ?: super.getContractAttachmentID(contractClassName)
    }

    private fun findOrImportAttachment(contractClassNames: List<ContractClassName>, data: ByteArray, attachments: MockAttachmentStorage): AttachmentId {
        val existingAttachment = attachments.files.filter {
            Arrays.equals(it.value.second, data)
        }
        return if (!existingAttachment.isEmpty()) {
            existingAttachment.keys.first()
        } else {
            attachments.importContractAttachment(contractClassNames, DEPLOYED_CORDAPP_UPLOADER, data.inputStream())
        }
    }
}
