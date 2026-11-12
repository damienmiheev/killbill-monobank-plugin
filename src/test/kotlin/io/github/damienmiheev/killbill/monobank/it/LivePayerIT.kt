/*
 * Copyright 2026 Dmytro Mikhieiev
 * SPDX-License-Identifier: Apache-2.0
 */
package io.github.damienmiheev.killbill.monobank.it

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The payer against a local copy of monobank's payment page (same element ids, a notice dialog on load, the card
 * form behind a button, a confirmation dialog after paying), so a change to the payer is checked before it meets the
 * real page. Runs with the live tests (`-Plive`), which download a browser.
 */
@Tag("live")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LivePayerIT {
    private lateinit var server: HttpServer
    private val submitted = CopyOnWriteArrayList<Map<String, String>>()
    private val confirmed = CopyOnWriteArrayList<String>()

    @BeforeAll
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/pay") { ex ->
            val body = PAGE.toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/submit") { ex ->
            val form =
                String(ex.requestBody.readAllBytes()).split('&').associate {
                    val (k, v) = it.split('=', limit = 2)
                    k to URLDecoder.decode(v, Charsets.UTF_8)
                }
            submitted += form
            ex.sendResponseHeaders(204, -1)
            ex.close()
        }
        server.createContext("/confirm") { ex ->
            confirmed += "yes"
            ex.responseHeaders.add("Location", "/done")
            ex.sendResponseHeaders(303, -1)
            ex.close()
        }
        server.createContext("/done") { ex ->
            val body = "<html><body><h1>Paid</h1></body></html>".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterAll
    fun stop() = server.stop(0)

    @Test
    fun `fills the card form, pays, and confirms the dialogs that follow`() {
        val shots = Path.of("target", "live-payer-it")
        // A published test card number, typed only into this local page.
        LivePayer(LivePayer.TestCard("4242424242424242", "12/30", "123"), shots).use { payer ->
            payer.pay("http://127.0.0.1:${server.address.port}/pay", "local") { confirmed.isNotEmpty() }
        }

        assertThat(submitted).hasSize(1)
        assertThat(submitted.single())
            .containsEntry("card", "4242424242424242")
            .containsEntry("exp", "12/30")
            .containsEntry("cvc", "123")
        assertThat(confirmed).hasSize(1)
        assertThat(Files.list(shots).use { it.count() }).isGreaterThanOrEqualTo(3)
    }

    private companion object {
        /** monobank's ids; fields format themselves like the real ones (spaced number, slash in the date). */
        val PAGE =
            """
            <!doctype html><html><head><meta charset="utf-8"><title>Payment form</title></head><body>
            <div id="notice"><p>Service notice</p><button id="service-provider-modal-btn" onclick="this.parentElement.remove()">Understand</button></div>
            <button id="show-payment-card" onclick="document.getElementById('card-form').style.display='block'">Pay with card</button>
            <form id="card-form" style="display:none" onsubmit="return false">
              <input id="cardNumber" autocomplete="cc-number"
                oninput="var d=this.value.replace(/\D/g,'').slice(0,16);this.value=d.replace(/(.{4})(?=.)/g,'$1 ')">
              <input id="expiresAt" autocomplete="cc-exp"
                oninput="var d=this.value.replace(/\D/g,'').slice(0,4);this.value=d.length>2?d.slice(0,2)+'/'+d.slice(2):d">
              <input id="cvc" type="password" autocomplete="cc-csc">
              <button id="payWithCardButton" type="submit" onclick="pay()">Pay 1 ₴</button>
            </form>
            <div id="confirm" style="display:none"><p>Confirm the payment</p>
              <button onclick="location.href='/confirm'">Confirm</button></div>
            <script>
            function pay(){
              var b='card='+encodeURIComponent(document.getElementById('cardNumber').value.replace(/ /g,''))
                +'&exp='+encodeURIComponent(document.getElementById('expiresAt').value)
                +'&cvc='+encodeURIComponent(document.getElementById('cvc').value);
              fetch('/submit',{method:'POST',body:b}).then(function(){
                document.getElementById('card-form').style.display='none';
                document.getElementById('confirm').style.display='block';});
            }
            </script></body></html>
            """.trimIndent()
    }
}
