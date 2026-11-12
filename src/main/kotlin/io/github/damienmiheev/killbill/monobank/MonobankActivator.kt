/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank

import io.github.damienmiheev.killbill.monobank.api.MonobankPaymentPluginApi
import io.github.damienmiheev.killbill.monobank.config.ConfigUnavailableException
import io.github.damienmiheev.killbill.monobank.config.SystemSettings
import io.github.damienmiheev.killbill.monobank.config.TenantSettingsProvider
import io.github.damienmiheev.killbill.monobank.core.CardService
import io.github.damienmiheev.killbill.monobank.core.KillBillSettler
import io.github.damienmiheev.killbill.monobank.core.NotificationHandler
import io.github.damienmiheev.killbill.monobank.core.PaymentService
import io.github.damienmiheev.killbill.monobank.core.PluginMetrics
import io.github.damienmiheev.killbill.monobank.crypto.CardTokenCipher
import io.github.damienmiheev.killbill.monobank.db.Db
import io.github.damienmiheev.killbill.monobank.db.NotificationDao
import io.github.damienmiheev.killbill.monobank.db.PaymentMethodDao
import io.github.damienmiheev.killbill.monobank.db.SchemaCheck
import io.github.damienmiheev.killbill.monobank.db.TransactionDao
import io.github.damienmiheev.killbill.monobank.health.MonobankHealthcheck
import io.github.damienmiheev.killbill.monobank.http.PluginServlet
import io.github.damienmiheev.killbill.monobank.mono.MonobankClients
import io.github.damienmiheev.killbill.monobank.reconcile.ReconcileJob
import org.killbill.billing.osgi.api.Healthcheck
import org.killbill.billing.osgi.api.OSGIPluginProperties
import org.killbill.billing.osgi.libs.killbill.KillbillActivatorBase
import org.killbill.billing.payment.plugin.api.PaymentPluginApi
import org.killbill.billing.tenant.api.TenantApiException
import org.killbill.billing.util.callcontext.TenantContext
import org.osgi.framework.BundleContext
import org.slf4j.LoggerFactory
import java.util.Hashtable
import javax.servlet.Servlet
import javax.servlet.http.HttpServlet

/** The name Kill Bill knows the plugin by: payment methods, routes and tenant config all use it. */
const val PLUGIN_NAME = "killbill-monobank"

/**
 * OSGi entry point. Start fails, and Kill Bill reports the plugin as not running, when the system settings
 * are invalid or the schema is missing: the plugin never runs half-configured.
 */
class MonobankActivator : KillbillActivatorBase() {
    override fun start(context: BundleContext) {
        super.start(context)
        try {
            startPlugin(context)
        } catch (e: Exception) {
            // anything that stops the start: clean up, then let Kill Bill report it
            // Undo what the base class started (its restart watcher, the Kill Bill service trackers).
            runCatching { super.stop(context) }
            throw e
        }
    }

    private fun startPlugin(context: BundleContext) {
        val settings = SystemSettings.load(property = configProperties::getString, env = System::getenv)
        SchemaCheck.require(dataSource.dataSource)

        val tenants = tenantSettings(settings)
        dispatcher.registerEventHandlers(tenants)

        val now = {
            clock.clock.utcNow
                .toDate()
                .toInstant()
        }
        val db = Db(dataSource.dataSource)
        val transactions = TransactionDao(db, now)
        val paymentMethods = PaymentMethodDao(db, now)
        val notificationDao = NotificationDao(db, now)
        val cipher = CardTokenCipher(settings.cardKeys, settings.currentCardKeyId, settings.tokenHmacKey)
        val clients = MonobankClients(settings)
        val metrics = PluginMetrics()
        val payments = PaymentService(transactions, paymentMethods, clients, tenants, cipher, metrics, now)
        val cards = CardService(paymentMethods, clients, tenants, cipher)
        val settler = KillBillSettler(killbillAPI, settings, transactions, metrics) { clock.clock.utcNow }
        val notifications =
            NotificationHandler(
                settings,
                tenants,
                clients,
                transactions,
                paymentMethods,
                notificationDao,
                payments,
                settler,
                metrics,
                now,
            )
        val healthcheck = MonobankHealthcheck(dataSource.dataSource, tenants, metrics)

        register(
            context,
            PaymentPluginApi::class.java,
            MonobankPaymentPluginApi(payments, cards, transactions, clock.clock),
        )
        register(context, Healthcheck::class.java, healthcheck)
        register(
            context,
            Servlet::class.java,
            PluginServlet(
                healthcheck,
                notifications,
                payments,
                settler,
                tenants,
                clients,
                killbillAPI.securityApi,
            ) as HttpServlet,
        )
        reconcile =
            ReconcileJob(
                settings,
                tenants,
                clients,
                transactions,
                paymentMethods,
                notificationDao,
                payments,
                cards,
                settler,
                metrics,
                now,
            ).also { it.start() }
        LOG.info("monobank plugin started: {}", settings)
    }

    private fun tenantSettings(settings: SystemSettings): TenantSettingsProvider {
        val tenantApi = killbillAPI.tenantUserApi
        return TenantSettingsProvider(
            readRaw =
                TenantSettingsProvider.killbillReader(
                    values = tenantApi::getTenantValuesForKey,
                    contextFor = ::tenantContext,
                ),
            boundAlias = { tenantId ->
                val apiKey =
                    try {
                        tenantApi.getTenantById(tenantId).apiKey
                    } catch (e: TenantApiException) {
                        throw ConfigUnavailableException("could not read the tenant (code ${e.code})", e)
                    }
                settings.merchantTokenBindings[apiKey]
            },
            env = System::getenv,
        )
    }

    override fun stop(context: BundleContext) {
        reconcile?.close()
        reconcile = null
        super.stop(context)
    }

    @Volatile
    private var reconcile: ReconcileJob? = null

    private fun <S> register(
        context: BundleContext,
        type: Class<S>,
        service: S,
    ) {
        val props = Hashtable<String, String>()
        props[OSGIPluginProperties.PLUGIN_NAME_PROP] = PLUGIN_NAME
        registrar.registerService(context, type, service, props)
    }

    private fun tenantContext(tenantId: java.util.UUID): TenantContext =
        object : TenantContext {
            override fun getAccountId(): java.util.UUID? = null

            override fun getTenantId(): java.util.UUID = tenantId
        }

    private companion object {
        val LOG = LoggerFactory.getLogger(MonobankActivator::class.java)
    }
}
