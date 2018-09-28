package net.corda.notary.raft

import net.corda.core.utilities.NetworkHostAndPort


data class RaftConfig(
        val nodeAddress: NetworkHostAndPort,
        val clusterAddresses: List<NetworkHostAndPort>
)