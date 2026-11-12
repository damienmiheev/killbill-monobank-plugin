/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.http

import com.fasterxml.jackson.databind.ObjectMapper
import org.killbill.billing.osgi.api.Healthcheck
import org.killbill.billing.tenant.api.Tenant
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * The plugin's HTTP routes under `/plugins/killbill-monobank/`.
 *
 * Kill Bill does not require authentication on plugin routes, so each route decides for itself.
 * This version serves only `GET /healthcheck`; anything else is 404.
 */
class PluginServlet(
    private val healthcheck: Healthcheck,
) : HttpServlet() {
    override fun doGet(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        when (req.pathInfo) {
            "/healthcheck" -> health(req, resp)
            else -> resp.sendError(HttpServletResponse.SC_NOT_FOUND)
        }
    }

    private fun health(
        req: HttpServletRequest,
        resp: HttpServletResponse,
    ) {
        // Kill Bill puts the tenant here when the request carries valid tenant API key headers.
        val tenant = req.getAttribute(TENANT_ATTRIBUTE) as? Tenant
        val status = healthcheck.getHealthStatus(tenant, null)
        resp.status = if (status.isHealthy) HttpServletResponse.SC_OK else HttpServletResponse.SC_SERVICE_UNAVAILABLE
        resp.contentType = "application/json"
        resp.characterEncoding = "UTF-8"
        resp.setHeader("Cache-Control", "no-store")
        JSON.writeValue(resp.outputStream, mapOf("healthy" to status.isHealthy, "details" to status.details))
    }

    private companion object {
        const val TENANT_ATTRIBUTE = "killbill_tenant"
        val JSON = ObjectMapper()
        private const val serialVersionUID = 1L
    }
}
