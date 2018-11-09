package com.template

import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.POUNDS
import net.corda.finance.contracts.asset.Cash
import java.time.LocalDate
import java.util.*

/**
 * Represents a job organised by a [developer] and carried out by a [contractor]. The job is split into a set of
 * [milestones].
 *
 * @param developer the developer in charge of the job.
 * @param contractor the contractor carrying out the job.
 * @param milestones the set of tasks to be completed.
 */
data class JobState(
        val developer: Party,
        val contractor: Party,
        val contractAmount: Double,  //the total agreement amount to complete the job
        val retentionPercentage: Double, //how much must be retained based on the invoice submitted
        val allowPaymentOnAccount: Boolean, //does the job allow for payment on accounts to be made
        val grossCumulativeAmount: Double = 0.0, //total amount of money we valued so far for completed milestones or milestones with payment on accounts
        val retentionAmount: Double = 0.0, //amount retained so far
        val netCumulativeValue: Double = 0.0, // grossCumulativeAmount minus retentionAmount
        val previousCumulativeValue: Double = 0.0, // netCumulativeValue (previous) - netCumulativeValue (current) (Valuation)
        val milestones: List<Milestone>,
        override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState {

    init {
        if (milestones.map { it.amount.token }.toSet().size != 1) {
            throw IllegalArgumentException("All milestones must be budgeted in the same currency.")
        }
    }

    override val participants = listOf(developer, contractor)
}

/**
 * Represents a milestone in a job.
 *
 * @param description the description of the work to be carried out as part of the milestone.
 * @param amount the amount paid for completing the milestone.
 * @param status the current status of the milestone.
 */
@CordaSerializable
data class Milestone(
        /** Which variables are constant and which change over time/event*/
        val reference: String,
        val description: String,
        val amount: Amount<Currency>, //milestone value
        val expectedEndDate: LocalDate,
        val percentageComplete: Double = 0.0,
        val requestedAmount: Amount<Currency> = 0.POUNDS, //amount as per invoice/payment application from the contractor
        val paymentOnAccount: Amount<Currency> = 0.POUNDS, //how much payment on account has been paid out (payment valuation)
        val netMilestonePayment: Amount<Currency> = 0.POUNDS, //calculated based on milestone amount/payment on account less retention percentage
        val documentsRequired : List<SecureHash> = listOf(),
        val remarks: String,
        val status: MilestoneStatus = MilestoneStatus.NOT_STARTED,
        val tasks: List<Task> = emptyList())

@CordaSerializable
data class Task(
        val reference: String,
        val description: String,
        val amount: Amount<Currency>,
        val expectedStartDate: LocalDate,
        val expectedDuration: Long,
        val requestedAmount: Amount<Currency> = 0.POUNDS, //amount as per invoice/payment application from the contractor
        val documentsRequired : List<SecureHash> = listOf(),
        val remarks: String,
        val status: TaskStatus = TaskStatus.NOT_STARTED)

@CordaSerializable
enum class MilestoneStatus { NOT_STARTED, STARTED, COMPLETED, ACCEPTED, PAID, ON_ACCOUNT_PAYMENT }

@CordaSerializable
enum class TaskStatus { NOT_STARTED, STARTED, COMPLETED, ACCEPTED }

@CordaSerializable
data class DocumentState(
        val name :   String,
        val description:  String,
        val type :  DocumentType,
        val issuer : Party,
        override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState {
    override val participants = listOf(issuer)
}

@CordaSerializable
enum class DocumentType { SURVEY }

class DocumentContract : Contract {
    companion object {
        const val DOCUMENT_CONTRACT_ID = "com.template.DocumentContract"
    }

    interface Commands : CommandData {
        class AddDocument : Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val documentCommand = tx.commandsOfType<DocumentContract.Commands>().single()
        val documentInputs = tx.inputsOfType<JobState>()

        when (documentCommand.value) {
            is Commands.AddDocument -> requireThat {
                "There should be no input states consumed" using (documentInputs.isEmpty())
                "There should be one output state" using (tx.outputsOfType<DocumentState>().size == 1)
            }
        }

    }
}

/**
 * Governs the evolution of [JobState]s.
 */
class JobContract : Contract {
    companion object {
        const val ID = "com.template.JobContract"
    }

    interface Commands : CommandData {
        class AgreeJob : Commands
        class StartTask(val milestoneIndex: Int, val taskIndex: Int) : Commands
        // `milestoneIndex` is the index of the milestone being updated in the list of milestones.
        class StartMilestone(val milestoneIndex: Int) : Commands
        class FinishTask(val milestoneIndex: Int, val taskIndex: Int) : Commands
        class FinishMilestone(val milestoneIndex: Int) : Commands
        class RejectMilestone(val milestoneIndex: Int) : Commands
        class AcceptMilestone(val milestoneIndex: Int) : Commands
        class PayMilestone(val milestoneIndex: Int) : Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val jobInputs = tx.inputsOfType<JobState>()
        val jobOutputs = tx.outputsOfType<JobState>()
        val jobCommand = tx.commandsOfType<JobContract.Commands>().single()

        when (jobCommand.value) {
            is Commands.AgreeJob -> requireThat {
                "No JobState inputs should be consumed." using (jobInputs.isEmpty())
                "One JobState output should be produced." using (jobOutputs.size == 1)

                val jobOutput = jobOutputs.single()
                "The developer and the contractor should be different parties." using (jobOutput.contractor != jobOutput.developer)
                "All the milestones should be unstarted." using
                        (jobOutput.milestones.all { it.status == MilestoneStatus.NOT_STARTED })

                "All tasks should be unstarted." using
                        (jobOutput.milestones.all { it ->
                            it.tasks.all {
                                it.status == TaskStatus.NOT_STARTED
                            }
                        })

                "All tasks should be of the same currency." using
                        jobOutput.milestones.all{ milestone ->
                            milestone.tasks.isEmpty() || milestone.tasks.distinctBy { it.amount.token }.size == 1
                        }

                "The total amount of each milestone should be equal to the accumulated amount of all tasks." using
                        (jobOutput.milestones.all { milestone ->
                            milestone.tasks.isEmpty() ||
                            (milestone.amount.quantity == milestone.tasks.sumByLong { it.amount.quantity } &&
                             milestone.amount.token == milestone.tasks.first().amount.token)
                        })

                fun checkTaskDates(expectedEndDate: LocalDate, tasks: List<Task>): Boolean {
                    var latest = LocalDate.MIN

                    tasks.forEach { task ->
                        val date = task.expectedStartDate.plusDays(task.expectedDuration)
                        if (date > latest) latest = date
                    }

                    return expectedEndDate == latest
                }

                "Expected end date of each milestone should be equal to the expected end date of the last task." using
                        (jobOutput.milestones.all {
                            it.tasks.isEmpty() || checkTaskDates(it.expectedEndDate, it.tasks)
                        })

                "The developer and contractor should be required signers." using
                        (jobCommand.signers.containsAll(listOf(jobOutput.contractor.owningKey, jobOutput.developer.owningKey)))
            }

            is Commands.StartTask -> requireThat {
                "One JobState input should be consumed." using (jobInputs.size == 1)
                "One JobState output should be produced." using (jobOutputs.size == 1)

                val jobInput = jobInputs.single()
                val jobOutput = jobOutputs.single()
                val milestoneIndex = (jobCommand.value as Commands.StartTask).milestoneIndex
                val taskIndex = (jobCommand.value as Commands.StartTask).taskIndex

                val inputModifiedMilestone = jobInput.milestones[milestoneIndex]
                val outputModifiedMilestone = jobOutput.milestones[milestoneIndex]
                val inputModifiedTask = jobInput.milestones[milestoneIndex].tasks[taskIndex]
                val outputModifiedTask = jobOutput.milestones[milestoneIndex].tasks[taskIndex]

                "The modified task should have an input status of NOT_STARTED." using
                        (inputModifiedTask.status == TaskStatus.NOT_STARTED)
                "The modified task should have an output status of STARTED." using
                        (outputModifiedTask.status == TaskStatus.STARTED)
                "Only the task status should change." using
                        (inputModifiedTask.copy(status = TaskStatus.STARTED) == outputModifiedTask)

                "The modified milestone should have an output status of STARTED." using
                        (outputModifiedMilestone.status == MilestoneStatus.STARTED)
                "The modified milestone's description and amount shouldn't change." using
                        (inputModifiedMilestone.copy(status = MilestoneStatus.STARTED) == outputModifiedMilestone)

                val otherInputTasks = jobInput.milestones.minusElement(inputModifiedTask)
                val otherOutputTasks = jobOutput.milestones.minusElement(outputModifiedTask)

                "All the other milestones should be unmodified." using
                        (otherInputTasks == otherOutputTasks)

                val otherInputMilestones = jobInput.milestones.minusElement(inputModifiedMilestone)
                val otherOutputMilestones = jobOutput.milestones.minusElement(outputModifiedMilestone)

                "All the other milestones should be unmodified." using
                        (otherInputMilestones == otherOutputMilestones)

                "The developer and contractor should be required signers." using
                        (jobCommand.signers.containsAll(listOf(jobOutput.contractor.owningKey, jobOutput.developer.owningKey)))
            }

            is Commands.StartMilestone -> requireThat {
                "One JobState input should be consumed." using (jobInputs.size == 1)
                "One JobState output should be produced." using (jobOutputs.size == 1)

                val jobInput = jobInputs.single()
                val jobOutput = jobOutputs.single()
                val milestoneIndex = (jobCommand.value as Commands.StartMilestone).milestoneIndex
                val inputModifiedMilestone = jobInput.milestones[milestoneIndex]
                val outputModifiedMilestone = jobOutput.milestones[milestoneIndex]

                "Cannot start a milestone if it has tasks." using (inputModifiedMilestone.tasks.isEmpty())

                "The modified milestone should have an input status of NOT_STARTED." using
                        (inputModifiedMilestone.status == MilestoneStatus.NOT_STARTED)
                "The modified milestone should have an output status of STARTED." using
                        (outputModifiedMilestone.status == MilestoneStatus.STARTED)
                "The modified milestone's description and amount shouldn't change." using
                        (inputModifiedMilestone.copy(status = MilestoneStatus.STARTED) == outputModifiedMilestone)

                val otherInputMilestones = jobInput.milestones.minusElement(inputModifiedMilestone)
                val otherOutputMilestones = jobOutput.milestones.minusElement(outputModifiedMilestone)

                "All the other milestones should be unmodified." using
                        (otherInputMilestones == otherOutputMilestones)

                "The developer and contractor should be required signers." using
                        (jobCommand.signers.containsAll(listOf(jobOutput.contractor.owningKey, jobOutput.developer.owningKey)))
            }

            is Commands.FinishTask -> requireThat {
                "One JobState input should be consumed." using (jobInputs.size == 1)
                "One JobState output should be produced." using (jobOutputs.size == 1)

                // TODO: Check dates

                val jobInput = jobInputs.single()
                val jobOutput = jobOutputs.single()
                val taskIndex = (jobCommand.value as Commands.FinishTask).taskIndex
                val milestoneIndex = (jobCommand.value as Commands.FinishMilestone).milestoneIndex
                val inputModifiedTask = jobInput.milestones[milestoneIndex].tasks[taskIndex]
                val outputModifiedTask = jobOutput.milestones[milestoneIndex].tasks[taskIndex]
                val inputModifiedMilestone = jobInput.milestones[milestoneIndex]
                val outputModifiedMilestone = jobOutput.milestones[milestoneIndex]

                "The modified task should have an input status of STARTED." using
                        (inputModifiedTask.status == TaskStatus.STARTED)
                "The modified task should have an output status of COMPLETED." using
                        (outputModifiedTask.status == TaskStatus.COMPLETED)
                "Only the status of the task should change" using
                        (inputModifiedTask.copy(status = TaskStatus.COMPLETED) == outputModifiedTask)

                val otherInputTasks = inputModifiedMilestone.tasks.minusElement(inputModifiedTask)
                val otherOutputTasks = outputModifiedMilestone.tasks.minusElement(outputModifiedTask)
                val otherInputMilestones = jobInput.milestones.minusElement(inputModifiedMilestone)
                val otherOutputMilestones = jobOutput.milestones.minusElement(outputModifiedMilestone)

                "All other tasks should be unmodified" using
                        (otherInputTasks == otherOutputTasks)
                "All the other milestones should be unmodified." using
                        (otherInputMilestones == otherOutputMilestones)

                "The contractor should be a required signer." using (jobCommand.signers.contains(jobOutputs.single().contractor.owningKey))
            }

            is Commands.FinishMilestone -> requireThat {
                "One JobState input should be consumed." using (jobInputs.size == 1)
                "One JobState output should be produced." using (jobOutputs.size == 1)

                // TODO: Check dates

                val jobInput = jobInputs.single()
                val jobOutput = jobOutputs.single()
                val milestoneIndex = (jobCommand.value as Commands.FinishMilestone).milestoneIndex
                val inputModifiedMilestone = jobInput.milestones[milestoneIndex]
                val outputModifiedMilestone = jobOutput.milestones[milestoneIndex]

                "All tasks should be finished" using
                        (inputModifiedMilestone.tasks.all { it.status == TaskStatus.COMPLETED })
                "The modified milestone should have an input status of STARTED." using
                        (inputModifiedMilestone.status == MilestoneStatus.STARTED)
                "The modified milestone should have an output status of COMPLETED." using
                        (outputModifiedMilestone.status == MilestoneStatus.COMPLETED)
                "The modified milestone's description and amount shouldn't change." using
                        (inputModifiedMilestone.copy(status = MilestoneStatus.COMPLETED) == outputModifiedMilestone)

                val otherInputMilestones = jobInput.milestones.minusElement(inputModifiedMilestone)
                val otherOutputMilestones = jobOutput.milestones.minusElement(outputModifiedMilestone)

                "All the other milestones should be unmodified." using
                        (otherInputMilestones == otherOutputMilestones)

                "The contractor should be a required signer." using (jobCommand.signers.contains(jobOutputs.single().contractor.owningKey))
            }

            is Commands.RejectMilestone -> requireThat {
                "One JobState input should be consumed." using (jobInputs.size == 1)
                "One JobState output should be produced." using (jobOutputs.size == 1)

                val jobInput = jobInputs.single()
                val jobOutput = jobOutputs.single()
                val milestoneIndex = (jobCommand.value as Commands.RejectMilestone).milestoneIndex
                val inputModifiedMilestone = jobInput.milestones[milestoneIndex]
                val outputModifiedMilestone = jobOutput.milestones[milestoneIndex]

                "The modified milestone should have an input status of COMPLETED." using
                        (inputModifiedMilestone.status == MilestoneStatus.COMPLETED)
                "The modified milestone should have an output status of STARTED." using
                        (outputModifiedMilestone.status == MilestoneStatus.STARTED)
                "The modified milestone's description and amount shouldn't change." using
                        (inputModifiedMilestone.copy(status = MilestoneStatus.STARTED) == outputModifiedMilestone)
                "All tasks should have output status of STARTED" using
                        (outputModifiedMilestone.tasks.isEmpty() || outputModifiedMilestone.tasks.all{
                            it.status == TaskStatus.STARTED
                        })

                val otherInputMilestones = jobInput.milestones.minusElement(inputModifiedMilestone)
                val otherOutputMilestones = jobOutput.milestones.minusElement(outputModifiedMilestone)

                "All the other milestones should be unmodified." using
                        (otherInputMilestones == otherOutputMilestones)

                "The developer should be a required signer." using (jobCommand.signers.contains(jobOutput.developer.owningKey))
            }

            is Commands.AcceptMilestone -> requireThat {
                "One JobState input should be consumed." using (jobInputs.size == 1)
                "One JobState output should be produced." using (jobOutputs.size == 1)

                val jobInput = jobInputs.single()
                val jobOutput = jobOutputs.single()
                val milestoneIndex = (jobCommand.value as Commands.AcceptMilestone).milestoneIndex
                val inputModifiedMilestone = jobInput.milestones[milestoneIndex]
                val outputModifiedMilestone = jobOutput.milestones[milestoneIndex]

                "The modified milestone should have an input status of COMPLETED." using
                        (inputModifiedMilestone.status == MilestoneStatus.COMPLETED)
                "The modified milestone should have an output status of ACCEPTED." using
                        (outputModifiedMilestone.status == MilestoneStatus.ACCEPTED)
                "The modified milestone's description and amount shouldn't change." using
                        (inputModifiedMilestone.copy(status = MilestoneStatus.ACCEPTED) == outputModifiedMilestone)
                "All tasks should have output status of ACCEPTED" using
                        (outputModifiedMilestone.tasks.isEmpty() || outputModifiedMilestone.tasks.all{
                            it.status == TaskStatus.ACCEPTED
                        })

                val otherInputMilestones = jobInput.milestones.minusElement(inputModifiedMilestone)
                val otherOutputMilestones = jobOutput.milestones.minusElement(outputModifiedMilestone)

                "All the other milestones should be unmodified." using
                        (otherInputMilestones == otherOutputMilestones)

                "The developer should be a required signer." using (jobCommand.signers.contains(jobOutput.developer.owningKey))
            }

            is Commands.PayMilestone -> requireThat {
                "One JobState input should be consumed." using (jobInputs.size == 1)
                "One JobState output should be produced." using (jobOutputs.size == 1)

                val jobInput = jobInputs.single()
                val jobOutput = jobOutputs.single()
                val milestoneIndex = (jobCommand.value as Commands.PayMilestone).milestoneIndex
                val inputModifiedMilestone = jobInput.milestones[milestoneIndex]
                val outputModifiedMilestone = jobOutput.milestones[milestoneIndex]

                "The modified milestone should have an input status of ACCEPTED." using
                        (inputModifiedMilestone.status == MilestoneStatus.ACCEPTED)
                "The modified milestone should have an output status of PAID." using
                        (outputModifiedMilestone.status == MilestoneStatus.PAID)
                "The modified milestone's description and amount shouldn't change." using
                        (inputModifiedMilestone.copy(status = MilestoneStatus.PAID) == outputModifiedMilestone)

                val otherInputMilestones = jobInput.milestones.minusElement(inputModifiedMilestone)
                val otherOutputMilestones = jobOutput.milestones.minusElement(outputModifiedMilestone)

                "All the other milestones should be unmodified." using
                        (otherInputMilestones == otherOutputMilestones)

                val cashInputs = tx.inputsOfType<Cash.State>()
                val cashOutputs = tx.outputsOfType<Cash.State>()
                "The Cash command should be Move" using (tx.commandsOfType<Cash.Commands.Move>().size == 1)
                val totalOutputCash = cashOutputs.map { it.amount.quantity }.sum()
                val outputContractorCash = cashOutputs.filter { it.owner == jobOutput.contractor }.map { it.amount.quantity }.sum()

                "The cash inputs and outputs should all be in the same currency as the modified milestone" using
                        ((cashInputs + cashOutputs).all { it.amount.token.product == inputModifiedMilestone.amount.token })
                "The cash inputs and outputs should have the same value" using
                        (cashInputs.map { it.amount.quantity }.sum() == totalOutputCash)
                "The cash outputs owned by the contractor should have the same value as the modified milestone" using
                        (outputContractorCash == outputModifiedMilestone.amount.quantity)
                // We cannot check that the remaining cash is returned to the developer, as the change outputs use
                // anonymous public keys.

                "The developer should be a required signer." using (jobCommand.signers.contains(jobInput.developer.owningKey))
            }

            else -> throw IllegalArgumentException("Unrecognised command ${jobCommand.value}.")
        }
    }
}
