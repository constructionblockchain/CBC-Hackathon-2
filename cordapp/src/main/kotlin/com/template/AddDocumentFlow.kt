package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Command
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker


@InitiatingFlow
@StartableByRPC
class AddDocumentFlow(val name: String, val description : String, val type : DocumentType) : FlowLogic<UniqueIdentifier>() {
    override val progressTracker = ProgressTracker()

    /**
     * Adding to the Document to the ledger
     */

    @Suspendable
    override fun call(): UniqueIdentifier {
        //Flow runner is a party and has identity - ourIdentity
        //TODO: investigate using serviceHub.getByNodeLegalEntity

        val outputState = DocumentState(name,description,type,ourIdentity)
        //owningKey is the key used to sign
        val signers = outputState.participants.map { it.owningKey }
        val command = Command(DocumentContract.Commands.AddDocument(), signers)


        val transactionBuilder = TransactionBuilder(notary = serviceHub.networkMapCache.notaryIdentities[0])
                .addOutputState(outputState, DocumentContract.DOCUMENT_CONTRACT_ID)
                .addCommand(command)

        transactionBuilder.verify(serviceHub)

        val fullySignedTransaction = serviceHub.signInitialTransaction(transactionBuilder)

        subFlow(FinalityFlow(fullySignedTransaction))

        return outputState.linearId
    }
}

