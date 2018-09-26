package net.corda.core.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.getOrThrow
import net.corda.finance.POUNDS
import net.corda.finance.contracts.asset.Cash
import net.corda.finance.issuedBy
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.internal.InternalMockNetwork
import net.corda.testing.node.internal.TestStartedNode
import net.corda.testing.node.internal.cordappsForPackages
import net.corda.testing.node.internal.startFlow
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.Test

class FinalityFlowOldApiTests {
    @Test
    fun `prevent use of the old API if the CorDapp target version is 4`() {
        val mockNet = InternalMockNetwork(cordappsForAllNodes = cordappsForPackages(
                "net.corda.finance.contracts.asset",
                "net.corda.finance.schemas",
                "net.corda.core.flows"
        ).map { it.withTargetVersion(4) }.toSet())
        val aliceNode = mockNet.createPartyNode(ALICE_NAME)
        val bobNode = mockNet.createPartyNode(BOB_NAME)
        val stx = aliceNode.signCashTransactionWith(bobNode, mockNet.defaultNotaryIdentity)
        val resultFuture = aliceNode.services.startFlow(CallOldFinalityFlow(stx)).resultFuture
        mockNet.runNetwork()
        assertThatIllegalArgumentException().isThrownBy {
            resultFuture.getOrThrow()
        }.withMessage("A flow session for each external participant to the transaction must be provided.")
        mockNet.stopNodes()
    }

    @Test
    fun `allow use of the old API if the CorDapp target version is 3`() {
        val mockNet = InternalMockNetwork(cordappsForAllNodes = cordappsForPackages(
                "net.corda.finance.contracts.asset",
                "net.corda.finance.schemas",
                "net.corda.core.flows"
        ).map { it.withTargetVersion(3) }.toSet())
        val aliceNode = mockNet.createPartyNode(ALICE_NAME)
        val bobNode = mockNet.createPartyNode(BOB_NAME)
        val stx = aliceNode.signCashTransactionWith(bobNode, mockNet.defaultNotaryIdentity)
        val future = aliceNode.services.startFlow(CallOldFinalityFlow(stx)).resultFuture
        mockNet.runNetwork()
        future.getOrThrow()
        assertThat(bobNode.services.validatedTransactions.getTransaction(stx.id)).isNotNull()
        mockNet.stopNodes()
    }

    private fun TestStartedNode.signCashTransactionWith(other: TestStartedNode, notary: Party): SignedTransaction {
        val amount = 1000.POUNDS.issuedBy(info.singleIdentity().ref(0))
        val builder = TransactionBuilder(notary)
        Cash().generateIssue(builder, amount, other.info.singleIdentity(), notary)
        return services.signInitialTransaction(builder)
    }

    @StartableByRPC
    class CallOldFinalityFlow(private val stx: SignedTransaction) : FlowLogic<SignedTransaction>() {
        @Suspendable
        @Suppress("DEPRECATION")
        override fun call(): SignedTransaction = subFlow(FinalityFlow(stx))
    }
}
