/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.http

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.damienmiheev.killbill.monobank.config.TenantSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettingsProvider
import io.github.damienmiheev.killbill.monobank.core.NotificationHandler
import io.github.damienmiheev.killbill.monobank.core.PaymentService
import io.github.damienmiheev.killbill.monobank.db.TxRow
import io.github.damienmiheev.killbill.monobank.health.MonobankHealthcheck
import io.github.damienmiheev.killbill.monobank.mono.MonobankClients
import org.killbill.billing.security.Logical
import org.killbill.billing.security.Permission
import org.killbill.billing.security.SecurityApiException
import org.killbill.billing.security.api.SecurityApi
import org.killbill.billing.tenant.api.Tenant
import org.killbill.billing.util.callcontext.TenantContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * The plugin's routes under `/plugins/killbill-monobank/` (design A7). Kill Bill does not authenticate plugin
 * routes, so each route decides: `notification` is authenticated by monobank's signature (and the optional shared
 * secret), `healthcheck` returns nothing sensitive, `cancel` and `pubkey` require Kill Bill credentials.
 */
class PluginServlet(
    private val healthcheck: MonobankHealthcheck,
    private val notifications: NotificationHandler,
    private val payments: PaymentService,
    /** Settles on another thread: the settler logs in as the plugin and would end the caller's session. */
    private val settleSoon: (TxRow) -> Unit,
    private val tenants: TenantSettingsProvider,
    private val clients: MonobankClients,
    private val security: SecurityApi,
) : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        when (req.pathInfo) {
            "/healthcheck" -> health(req, resp)
            "/pubkey" -> publicKey(req, resp)
            else -> resp.sendError(HttpServletResponse.SC_NOT_FOUND)
        }
    }

    override fun doPost(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        val path = req.pathInfo.orEmpty()
        val cancel = CANCEL.matchEntire(path)
        when {
            path == "/notification" -> notification(req, resp)
            cancel != null -> cancel(req, resp, cancel.groupValues[1])
            else -> resp.sendError(HttpServletResponse.SC_NOT_FOUND)
        }
    }

    private fun notification(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        val body =
            readLimited(req)
                ?: return json(resp, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, mapOf("error" to "too large"))
        val result = notifications.handle(body, req.getHeader("X-Sign"), req.getHeader("X-Notification-Secret"))
        json(resp, result.httpStatus, mapOf("result" to result.name))
    }

    /** Gives up an unpaid hosted page: 200 when cancelled, 409 when the customer paid at the same moment. */
    private fun cancel(
        req: HttpServletRequest,
        resp: HttpServletResponse,
        paymentIdText: String,
    ) {
        val tenant = authorized(req, resp, Permission.PAYMENT_CAN_TRANSITION) ?: return
        val paymentId =
            runCatching { UUID.fromString(paymentIdText) }.getOrNull()
                ?: return resp.sendError(HttpServletResponse.SC_NOT_FOUND)
        when (val outcome = payments.cancelHosted(paymentId, tenant.id)) {
            is PaymentService.Cancellation.Done -> {
                settle(outcome.row)
                json(resp, HttpServletResponse.SC_OK, describe(outcome.row))
            }

            is PaymentService.Cancellation.AlreadyPaid -> {
                settle(outcome.row)
                json(resp, HttpServletResponse.SC_CONFLICT, describe(outcome.row))
            }

            PaymentService.Cancellation.NotFound -> {
                json(
                    resp,
                    HttpServletResponse.SC_NOT_FOUND,
                    mapOf("error" to "no hosted monobank payment with this id"),
                )
            }

            is PaymentService.Cancellation.Unavailable -> {
                json(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, mapOf("error" to outcome.reason))
            }
        }
    }

    /** monobank's current public webhook key for the caller's tenant, so a forwarder can verify without the token. */
    private fun publicKey(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        val tenant = authorized(req, resp, null) ?: return
        val settings =
            (tenants.get(tenant.id) as? TenantSettings.ParseResult.Valid)?.settings
                ?: return json(
                    resp,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    mapOf("error" to "tenant settings are not valid"),
                )
        val key =
            clients.forToken(settings.merchantToken).webhooks.currentPublicKey()
                ?: return json(
                    resp,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    mapOf("error" to "monobank's key is unavailable"),
                )
        json(resp, HttpServletResponse.SC_OK, mapOf("key" to key))
    }

    private fun health(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        val tenant = req.getAttribute(TENANT_ATTRIBUTE) as? Tenant
        val status =
            if (security.isSubjectAuthenticated) {
                healthcheck.detailedStatus(tenant)
            } else {
                healthcheck.getHealthStatus(tenant, null)
            }
        json(
            resp,
            if (status.isHealthy) HttpServletResponse.SC_OK else HttpServletResponse.SC_SERVICE_UNAVAILABLE,
            mapOf("healthy" to status.isHealthy, "details" to status.details),
        )
    }

    /** The tenant of an authenticated Kill Bill caller with [permission], or null after answering 401/403. */
    private fun authorized(
        req: HttpServletRequest,
        resp: HttpServletResponse,
        permission: Permission?,
    ): Tenant? {
        val tenant = req.getAttribute(TENANT_ATTRIBUTE) as? Tenant
        if (!security.isSubjectAuthenticated || tenant == null) {
            json(
                resp,
                HttpServletResponse.SC_UNAUTHORIZED,
                mapOf("error" to "Kill Bill credentials and tenant headers required"),
            )
            return null
        }
        if (permission != null) {
            try {
                security.checkCurrentUserPermissions(listOf(permission), Logical.AND, TenantOnly(tenant.id))
            } catch (e: SecurityApiException) {
                LOG.info("Refused {} without {}", req.pathInfo, permission, e)
                json(resp, HttpServletResponse.SC_FORBIDDEN, mapOf("error" to "missing permission"))
                return null
            }
        }
        return tenant
    }

    private fun settle(row: TxRow) = settleSoon(row)

    private fun describe(row: TxRow): Map<String, Any?> =
        mapOf(
            "paymentId" to row.paymentId,
            "transactionId" to row.transactionId,
            "state" to row.state.name,
            "invoiceId" to row.invoiceId,
        )

    private fun readLimited(req: HttpServletRequest): ByteArray? {
        if (req.contentLengthLong > MAX_BODY) return null
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        req.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_BODY) return null
            }
        }
        return out.toByteArray()
    }

    private fun json(
        resp: HttpServletResponse,
        status: Int,
        body: Any,
    ) {
        resp.status = status
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "no-store")
        JSON.writeValue(resp.outputStream, body)
    }

    private class TenantOnly(
        private val tenantId: UUID,
    ) : TenantContext {
        override fun getAccountId(): UUID? = null

        override fun getTenantId(): UUID = tenantId
    }

    private companion object {
        const val TENANT_ATTRIBUTE = "killbill_tenant"
        const val MAX_BODY = 64 * 1024
        const val BUFFER = 8 * 1024
        val CANCEL = Regex("/payments/([0-9a-fA-F-]{36})/cancel")
        val JSON = ObjectMapper()
        val LOG = LoggerFactory.getLogger(PluginServlet::class.java)
        private const val serialVersionUID = 1L
    }
}
