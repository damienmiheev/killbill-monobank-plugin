/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.it

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pays a monobank **test-mode** payment page the way a customer does: card number, expiry, CVC, pay, and every
 * dialog the page shows after that. With a test token monobank accepts any Luhn-valid number and charges nothing.
 *
 * The card comes from `MONOBANK_TEST_CARD` (`number|MM/YY|CVC`), so no card data lives in the code. Each step is
 * saved as a screenshot under `target/live-screenshots/`, which the live workflow uploads.
 */
class LivePayer(
    private val card: TestCard,
    private val screenshots: Path = Path.of("target", "live-screenshots"),
    private val headless: Boolean = true,
) : AutoCloseable {
    data class TestCard(
        val number: String,
        val expiry: String,
        val cvc: String,
    ) {
        override fun toString(): String = "TestCard(…${number.takeLast(4)})"

        companion object {
            /** `number|MM/YY|CVC`, or null when unset. */
            fun fromEnv(value: String?): TestCard? =
                value?.split('|')?.takeIf { it.size == 3 }?.let { (n, e, c) -> TestCard(n.trim(), e.trim(), c.trim()) }
        }
    }

    private val playwright: Playwright = Playwright.create()
    private val browser: Browser =
        playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(headless))

    /**
     * Pays [pageUrl] and returns once the page no longer shows the card form, or after [settled] returns true (the
     * caller's check against Kill Bill or monobank). [name] labels the screenshots.
     */
    fun pay(
        pageUrl: String,
        name: String,
        settled: () -> Boolean = { false },
    ) {
        Files.createDirectories(screenshots)
        browser.newContext(Browser.NewContextOptions().setLocale("en-US")).use { context ->
            val page = context.newPage()
            var step = 0

            fun shot(label: String) {
                step++
                page.screenshot(
                    Page.ScreenshotOptions().setPath(screenshots.resolve("$name-$step-$label.png")).setFullPage(true),
                )
            }
            page.navigate(pageUrl)
            page.waitForLoadState()
            dismissDialogs(page)
            if (!page.isVisible(CARD_NUMBER)) clickIfVisible(page, SHOW_CARD_FORM)
            page.waitForSelector(CARD_NUMBER)
            shot("form")
            // Typed key by key, as the page formats the fields while a person types.
            page.click(CARD_NUMBER)
            page.keyboard().type(
                card.number,
                com.microsoft.playwright.Keyboard
                    .TypeOptions()
                    .setDelay(TYPE_DELAY_MS),
            )
            page.click(EXPIRY)
            page.keyboard().type(
                card.expiry.replace("/", ""),
                com.microsoft.playwright.Keyboard
                    .TypeOptions()
                    .setDelay(TYPE_DELAY_MS),
            )
            page.click(CVC)
            page.keyboard().type(
                card.cvc,
                com.microsoft.playwright.Keyboard
                    .TypeOptions()
                    .setDelay(TYPE_DELAY_MS),
            )
            // No screenshot of the filled form: the card number must not be in an uploaded file.
            page.click(PAY)
            // After paying, monobank may show confirmation or information dialogs (test 3-D Secure, card saving,
            // service notices). Confirm each until the form is gone or the payment has settled.
            val deadline = System.nanoTime() + PAY_TIMEOUT_NANOS
            var done = false
            while (!done && System.nanoTime() < deadline) {
                page.waitForTimeout(STEP_WAIT_MS)
                if (dismissDialogs(page)) shot("dialog")
                done = settled()
            }
            shot(if (done) "done" else "timeout")
            check(done) { "the $name page did not settle within ${PAY_TIMEOUT_NANOS / 1_000_000_000} s" }
        }
    }

    /**
     * Confirms a monobank test 3-D Secure page ([tdsUrl], a saved-card payment monobank wants confirmed): presses
     * its confirm button until the payment [settled], never a decline.
     */
    fun confirm(
        tdsUrl: String,
        name: String,
        settled: () -> Boolean,
    ) {
        Files.createDirectories(screenshots)
        browser.newContext(Browser.NewContextOptions().setLocale("en-US")).use { context ->
            val page = context.newPage()
            page.navigate(tdsUrl)
            page.waitForLoadState()
            page.screenshot(Page.ScreenshotOptions().setPath(screenshots.resolve("$name-1-tds.png")).setFullPage(true))
            val deadline = System.nanoTime() + PAY_TIMEOUT_NANOS
            var done = false
            while (!done && System.nanoTime() < deadline) {
                dismissDialogs(page)
                page.waitForTimeout(STEP_WAIT_MS)
                done = settled()
            }
            page.screenshot(
                Page.ScreenshotOptions().setPath(screenshots.resolve("$name-2-${if (done) "done" else "timeout"}.png")),
            )
            check(done) { "the $name 3-D Secure page did not settle within ${PAY_TIMEOUT_NANOS / 1_000_000_000} s" }
        }
    }

    /** Clicks every visible confirm / acknowledge button once; true when one was clicked. */
    private fun dismissDialogs(page: Page): Boolean {
        var clicked = false
        for (selector in DIALOG_BUTTONS) clicked = clickIfVisible(page, selector) || clicked
        // Only a button whose whole label is a confirmation: never "Continue without saving", "Do not confirm"...
        val buttons = page.locator("button:visible", Page.LocatorOptions().setHasText(CONFIRM_LABEL))
        for (i in 0 until buttons.count()) {
            val button = buttons.nth(i)
            val label = button.innerText().trim()
            if (DENY.containsMatchIn(label) || button.isDisabled) continue
            button.click()
            clicked = true
            break
        }
        return clicked
    }

    private fun clickIfVisible(
        page: Page,
        selector: String,
    ): Boolean {
        if (!page.isVisible(selector)) return false
        page.click(selector)
        return true
    }

    override fun close() {
        runCatching { browser.close() }
        runCatching { playwright.close() }
    }

    companion object {
        // monobank's payment page (read from the live page, 2026-11-12).
        const val SHOW_CARD_FORM = "#show-payment-card"
        const val CARD_NUMBER = "#cardNumber"
        const val EXPIRY = "#expiresAt"
        const val CVC = "#cvc"
        const val PAY = "#payWithCardButton"
        val DIALOG_BUTTONS = listOf("#service-provider-modal-btn", "#save-browser-info-modal-btn")

        /** A button whose whole label confirms, in English or Ukrainian. */
        val CONFIRM_LABEL: java.util.regex.Pattern =
            java.util.regex.Pattern.compile(
                "^\\s*(Confirm|Підтвердити|Understand|Зрозуміло|Continue|Продовжити|Success|Успішно|OK)\\s*$",
                java.util.regex.Pattern.CASE_INSENSITIVE,
            )

        /** Never clicked, whatever else the label says. */
        val DENY = Regex("without|cancel|decline|fail|не |без |скасув|відмов|відхил", RegexOption.IGNORE_CASE)

        private const val TYPE_DELAY_MS = 40.0
        private const val STEP_WAIT_MS = 1500.0
        private const val PAY_TIMEOUT_NANOS = 90_000_000_000L
    }
}
