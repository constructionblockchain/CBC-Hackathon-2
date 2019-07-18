package com.template.contracts

import com.template.JobContract
import com.template.JobState
import com.template.Milestone
import com.template.MilestoneStatus
import com.template.Task
import net.corda.core.identity.CordaX500Name
import net.corda.finance.DOLLARS
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.Test
import java.time.LocalDate

class StartMilestoneCommandTests {
    private val ledgerServices = MockServices(listOf("com.template"))
    private val developer = TestIdentity(CordaX500Name("John Doe", "City", "GB"))
    private val contractor = TestIdentity(CordaX500Name("Richard Roe", "Town", "GB"))
    private val milestoneOne = Milestone(reference = "M1",
            description = "Fit windows.",
            amount = 100.DOLLARS,
            expectedEndDate = LocalDate.now(),
            remarks = "No remarks.")
    private val milestoneTwo = Milestone(reference = "M2",
            description = "Fit doors.",
            amount = 200.DOLLARS,
            expectedEndDate = LocalDate.now(),
            remarks = "No remarks")
    private val milestoneTwoStarted  = milestoneTwo.copy(status = MilestoneStatus.STARTED)
    private val participants = listOf(developer.publicKey, contractor.publicKey)
    private val unstartedJobState = JobState(
            milestones = listOf(milestoneOne, milestoneTwo),
            developer = developer.party,
            contractor = contractor.party,
            allowPaymentOnAccount = true,
            contractAmount = 100.0,
            retentionPercentage = 5.0
    )
    private val startedJobState = unstartedJobState.copy(milestones = listOf(milestoneOne, milestoneTwoStarted))
    private val milestoneIndex = 1
    private val task = Task(
        reference = "T1",
        description = "Procure glass",
        amount = 100.DOLLARS,
        expectedStartDate = LocalDate.now().minusDays(1),
        expectedDuration = 1,
        remarks = "No remarks"
    )
    private val milestoneWithTask = milestoneTwo.copy(tasks = listOf(task))

    @Test
    fun `StartMilestone command should complete successfully`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                output(JobContract.ID, startedJobState)
                verifies()
            }
        }
    }

    @Test
    fun `One JobState input should be consumed`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                output(JobContract.ID, startedJobState)
                failsWith("One JobState input should be consumed.")
            }
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                input(JobContract.ID, unstartedJobState)
                output(JobContract.ID, startedJobState)
                failsWith("One JobState input should be consumed.")
            }
        }
    }

    @Test
    fun `One JobState output should be produced`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                failsWith("One JobState output should be produced.")
            }
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                output(JobContract.ID, startedJobState)
                output(JobContract.ID, startedJobState)
                failsWith("One JobState output should be produced.")
            }
        }
    }

    @Test
    fun `Cannot start a milestone if it has tasks`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState.copy(milestones = listOf(milestoneOne, milestoneWithTask)))
                output(JobContract.ID, startedJobState.copy(milestones = listOf(
                    milestoneOne,
                    milestoneWithTask.copy(status = MilestoneStatus.STARTED))))
                failsWith("Cannot start a milestone if it has tasks.")
            }
        }
    }

    @Test
    fun `The modified milestone should have an input status of UNSTARTED`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, startedJobState)
                output(JobContract.ID, startedJobState)
                failsWith("The modified milestone should have an input status of NOT_STARTED.")
            }
        }
    }

    @Test
    fun `The modified milestone should have an output status of STARTED`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                output(JobContract.ID, unstartedJobState)
                failsWith("The modified milestone should have an output status of STARTED.")
            }
        }
    }

    @Test
    fun `The modified milestone's description and amount shouldn't change`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                output(JobContract.ID, startedJobState.copy(
                    milestones = listOf(milestoneOne, milestoneTwoStarted.copy(description = "Fit some floors."))))
                failsWith("The modified milestone's description and amount shouldn't change.")
            }
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                output(JobContract.ID, startedJobState.copy(
                    milestones = listOf(milestoneOne, milestoneTwoStarted.copy(amount = 300.DOLLARS))))
                failsWith("The modified milestone's description and amount shouldn't change.")
            }
        }
    }

    @Test
    fun `All the other milestones should be unmodified`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                output(JobContract.ID, startedJobState.copy(
                    milestones = listOf(milestoneOne.copy(description = "Fit some floors."), milestoneTwoStarted)))
                failsWith("All the other milestones should be unmodified.")
            }
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                output(JobContract.ID, startedJobState.copy(
                    milestones = listOf(milestoneOne.copy(amount = 200.DOLLARS), milestoneTwoStarted)))
                failsWith("All the other milestones should be unmodified.")
            }
            transaction {
                command(participants, JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                output(JobContract.ID, startedJobState.copy(
                    milestones = listOf(milestoneOne.copy(status = MilestoneStatus.STARTED), milestoneTwoStarted)))
                failsWith("All the other milestones should be unmodified.")
            }
        }
    }

    @Test
    fun `Both developer and contractor should sign the transaction`() {
        ledgerServices.ledger {
            transaction {
                command(listOf(developer.publicKey), JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                output(JobContract.ID, startedJobState)
                failsWith("The developer and contractor should be required signers.")
            }
            transaction {
                command(listOf(contractor.publicKey), JobContract.Commands.StartMilestone(milestoneIndex))
                input(JobContract.ID, unstartedJobState)
                output(JobContract.ID, startedJobState)
                failsWith("The developer and contractor should be required signers.")
            }
        }
    }
}
