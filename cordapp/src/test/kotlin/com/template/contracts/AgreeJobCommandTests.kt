package com.template.contracts

import com.template.JobContract
import com.template.JobState
import com.template.Milestone
import com.template.Task
import com.template.MilestoneStatus
import com.template.TaskStatus
import net.corda.core.identity.CordaX500Name
import net.corda.finance.DOLLARS
import net.corda.finance.POUNDS
import net.corda.testing.core.TestIdentity
import net.corda.testing.node.MockServices
import net.corda.testing.node.ledger
import org.junit.Test
import java.time.LocalDate

class AgreeJobCommandTests {
    private val ledgerServices = MockServices(listOf("com.template"))
    private val developer = TestIdentity(CordaX500Name("John Doe", "City", "GB"))
    private val contractor = TestIdentity(CordaX500Name("Richard Roe", "Town", "GB"))
    private val unstartedTask = Task(
        reference = "T1",
        description = "Procure glass",
        amount = 80.DOLLARS,
        expectedStartDate = LocalDate.now(),
        expectedDuration = 3,
        remarks = "No remarks"
    )
    private val unstartedTaskTwo = Task(
        reference = "T2",
        description = "Install glass",
        amount = 20.DOLLARS,
        expectedStartDate = LocalDate.now().plusDays(3),
        expectedDuration = 2,
        remarks = "No remarks"
    )
    private val milestone = Milestone(reference="M1",
                                      description = "Fit windows.",
                                      amount = 100.DOLLARS,
                                      expectedEndDate = LocalDate.now().plusDays(5),
                                      percentageComplete = 50.0,
                                      requestedAmount = 100.DOLLARS,
                                      remarks = "No remarks",
                                      tasks = listOf(unstartedTask, unstartedTaskTwo))
    private val participants = listOf(developer.publicKey, contractor.publicKey)
    private val jobState = JobState(
        developer = developer.party,
        contractor = contractor.party,
        contractAmount = 150.0,
        retentionPercentage = 5.0,
        allowPaymentOnAccount = true,
        milestones = listOf(milestone)
    )

    @Test
    fun `AgreeJob command should complete successfully`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState)
                verifies()
            }
        }
    }

    @Test
    fun `No JobState inputs should be consumed`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                input(JobContract.ID, jobState)
                output(JobContract.ID, jobState)
                failsWith("No JobState inputs should be consumed.")
            }
        }
    }

    @Test
    fun `One JobState output should be produced`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState)
                output(JobContract.ID, jobState)
                failsWith("One JobState output should be produced.")
            }
        }
    }

    @Test
    fun `The developer should be different to the contractor`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState.copy(developer = contractor.party))
                failsWith("The developer and the contractor should be different parties.")
            }
        }
    }

    @Test
    fun `All the milestones should be unstarted`() {
        ledgerServices.ledger {
            // A single started milestone.
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState.copy(
                        milestones = listOf(milestone.copy(status = MilestoneStatus.STARTED))))
                failsWith("All the milestones should be unstarted.")
            }
            // An unstarted milestone first, followed by a started milestone.
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState.copy(
                        milestones = listOf(milestone, milestone.copy(status = MilestoneStatus.STARTED))))
                failsWith("All the milestones should be unstarted.")
            }
        }
    }

    @Test
    fun `All tasks should be unstarted`() {
        ledgerServices.ledger {
            // A single started task.
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState.copy(
                    milestones = listOf(milestone.copy(tasks = listOf(unstartedTask.copy(status=TaskStatus.STARTED))))))
                failsWith("All tasks should be unstarted.")
            }
            // An unstarted task first, followed by a started task.
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState.copy(
                    milestones = listOf(milestone, milestone.copy(
                        tasks = listOf(unstartedTask.copy(status=TaskStatus.STARTED),
                                       unstartedTaskTwo)))))
                failsWith("All tasks should be unstarted.")
            }
        }
    }

    @Test
    fun `All tasks should be of the same currency`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState.copy(
                    milestones = listOf(milestone, milestone.copy(
                        tasks = listOf(unstartedTask.copy(amount = 80.POUNDS),
                                       unstartedTaskTwo)))))
                failsWith("All tasks should be of the same currency.")
            }
        }
    }

    @Test
    fun `The total amount of each milestone should be equal to the accumulated amount of all tasks`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState.copy(
                    milestones = listOf(
                        milestone.copy(
                            tasks = listOf(
                                unstartedTask.copy(amount = 180.DOLLARS),
                                unstartedTaskTwo
                            )
                        )
                    )
                ))
                failsWith("The total amount of each milestone should be equal to the accumulated amount of all tasks.")
            }
        }
    }

    @Test
    fun `Expected end date of each milestone should be equal to the expected end date of the last task`() {
        ledgerServices.ledger {
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState.copy(
                    milestones = listOf(
                        milestone.copy(
                            tasks = listOf(
                                unstartedTask.copy(expectedStartDate = LocalDate.now().plusDays(6)),
                                unstartedTaskTwo
                            )
                        )
                    )
                ))
                failsWith("Expected end date of each milestone should be equal to the expected end date of the last task.")
            }
            transaction {
                command(participants, JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState.copy(
                    milestones = listOf(
                        milestone.copy(
                            tasks = listOf(
                                unstartedTask.copy(expectedStartDate = LocalDate.now(), expectedDuration = 6),
                                unstartedTaskTwo
                            )
                        )
                    )
                ))
                failsWith("Expected end date of each milestone should be equal to the expected end date of the last task.")
            }
        }
    }

    @Test
    fun `Both the developer and the contractor should be signers of the transaction`() {
        ledgerServices.ledger {
            transaction {
                command(listOf(developer.publicKey), JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState)
                failsWith("The developer and contractor should be required signers.")
            }
            transaction {
                command(listOf(contractor.publicKey), JobContract.Commands.AgreeJob())
                output(JobContract.ID, jobState)
                failsWith("The developer and contractor should be required signers.")
            }
        }
    }
}
