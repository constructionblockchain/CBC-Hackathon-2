package com.template.server.controllers

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.template.*
import com.template.server.NodeRPCConnection
import net.corda.core.contracts.Amount
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.CordaX500Name
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Define CorDapp-specific endpoints in a controller such as this.
 */
@RestController
@RequestMapping("/flows") // The paths for GET and POST requests are relative to this base path.
class FlowController(rpc: NodeRPCConnection) {
    companion object {
        private val logger = LoggerFactory.getLogger(RestController::class.java)
    }

    private val proxy = rpc.proxy
    private val gson = Gson()

    @PostMapping(value = "/agreejob")
    private fun agreeJob(@RequestBody() jsonBody :String): ResponseEntity<*> {
        var fromJson = gson.fromJson<Map<String, Any>>(jsonBody, object : TypeToken<Map<String, Any>>() {}.type)
        val contractorName = fromJson["contractor"].toString()
        val notaryName = fromJson["notary"].toString()
        val contractAmount = fromJson["contractAmount"].toString().toDoubleOrNull()
        val retentionPercentage = fromJson["retentionPercentage"].toString().toDoubleOrNull()
        val allowPaymentOnAccount = fromJson["allowPaymentOnAccount"].toString().toBoolean()

        var milestoneJson : List<Map<String,String>> =  fromJson["milestones"] as List<Map<String,String>>

        val milestones = milestoneJson.map { milestone ->
            val reference = milestone["reference"].toString()
            val quantity = milestone["amount"].toString()
            val description = milestone["description"].toString()
            val expectedEndDateString = milestone["expectedEndDate"]
            val expectedEndDate = LocalDate.parse(expectedEndDateString, DateTimeFormatter.ISO_DATE)
            val amount = Amount(quantity.toLong(), Currency.getInstance(milestone["currency"]))
            val remarks = milestone["remarks"].toString()
            Milestone(reference=reference, description=description, amount=amount, expectedEndDate=expectedEndDate, remarks=remarks)
        }

        val contractor = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(contractorName))
                ?: return ResponseEntity<Any>("Contractor $contractorName not found on network.", HttpStatus.INTERNAL_SERVER_ERROR)
        val notary = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(notaryName))
                ?: return ResponseEntity<Any>("Notary $notaryName not found on network.", HttpStatus.INTERNAL_SERVER_ERROR)


        val linearId = proxy.startFlowDynamic(AgreeJobFlow::class.java,
                contractor,
                contractAmount,
                retentionPercentage,
                allowPaymentOnAccount,
                milestones,
                notary).returnValue.get()

        return ResponseEntity<Any>("New job created with ID ${linearId.id}.", HttpStatus.CREATED)
    }

    @PostMapping(value = "/startmilestone")
    private fun startmilestone(
            @RequestParam("linear-id") linearId: String,
            @RequestParam("milestone-index") milestoneIndex: Int
    ): ResponseEntity<*> {
        proxy.startFlowDynamic(StartMilestoneFlow::class.java, UniqueIdentifier.fromString(linearId), milestoneIndex).returnValue.get()

        return ResponseEntity<Any>("Milestone # $milestoneIndex started for Job ID $linearId.", HttpStatus.OK)
    }

    @PostMapping(value = "/finishmilestone")
    private fun finishmilestone(
            @RequestParam("linear-id") linearId: String,
            @RequestParam("milestone-index") milestoneIndex: Int
    ): ResponseEntity<*> {
        proxy.startFlowDynamic(CompleteMilestoneFlow::class.java, UniqueIdentifier.fromString(linearId), milestoneIndex).returnValue.get()

        return ResponseEntity<Any>("Milestone # $milestoneIndex finished for Job ID $linearId.", HttpStatus.OK)
    }

    @PostMapping(value = "/acceptmilestone")
    private fun acceptmilestone(
            @RequestParam("linear-id") linearId: String,
            @RequestParam("milestone-index") milestoneIndex: Int
    ): ResponseEntity<*> {
        val id = UniqueIdentifier.fromString(linearId)
        proxy.startFlowDynamic(AcceptOrRejectFlow::class.java, id, true, milestoneIndex).returnValue.get()
        return ResponseEntity<Any>("Job milestone with id $milestoneIndex was successfully accepted!",
                HttpStatus.OK)
    }

    @PostMapping(value = "/rejectmilestone")
    private fun rejectmilestone(
            @RequestParam("linear-id") linearId: String,
            @RequestParam("milestone-index") milestoneIndex: Int
    ): ResponseEntity<*> {
        val id = UniqueIdentifier.fromString(linearId)
        proxy.startFlowDynamic(AcceptOrRejectFlow::class.java, id, false, milestoneIndex).returnValue.get()
        return ResponseEntity<Any>("Job milestone with id $milestoneIndex was successfully rejected!",
                HttpStatus.OK)
    }

    @PostMapping(value = "/paymilestone")
    private fun paymilestone(
            @RequestParam("id") idString: String,
            @RequestParam("milestone-index") milestoneIndex: Int
    ): ResponseEntity<*> {
        val id = UniqueIdentifier.fromString(idString)

        val linearId = proxy.startFlowDynamic(PayFlow::class.java, id, milestoneIndex).returnValue.get()

        return ResponseEntity<Any>("Milestone $milestoneIndex of job ${linearId.id} paid.", HttpStatus.CREATED)
    }

    @PostMapping(value = "/issuecash")
    private fun issuecash(
            @RequestParam("quantity") quantity: String,
            @RequestParam("currency") currency: String,
            @RequestParam("notary") notaryName: String
    ): ResponseEntity<*> {
        val amount = Amount(quantity.toLong(), Currency.getInstance(currency))
        val notary = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(notaryName))
                ?: return ResponseEntity<Any>("Notary $notaryName not found on network.", HttpStatus.INTERNAL_SERVER_ERROR)

        proxy.startFlowDynamic(IssueCashFlow::class.java, amount, notary).returnValue.get()

        return ResponseEntity<Any>("$quantity of $currency issued.", HttpStatus.CREATED)
    }
}
