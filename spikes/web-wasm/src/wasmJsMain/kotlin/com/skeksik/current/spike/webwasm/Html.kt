package com.skeksik.current.spike.webwasm

import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLVideoElement

// The HTML patches over (and under) the Compose canvas. Styles for them are in index.html.

/** A transparent `<a>` laid over a Compose-drawn card: the browser sees a real link (middle-click, new tab, copy link, Tab focus). */
internal fun createLinkOverlay(onPlainClick: (String) -> Unit): HTMLAnchorElement = js(
    """(function () {
        var a = document.createElement('a');
        a.className = 'c-link';
        a.addEventListener('click', function (e) {
            if (e.button === 0 && !e.ctrlKey && !e.metaKey && !e.shiftKey && !e.altKey) {
                e.preventDefault();
                onPlainClick(a.getAttribute('href'));
            }
        });
        // HTML elements over the canvas take its input, so scrolling over a card would stop the Compose list: hand the wheel back.
        a.addEventListener('wheel', function (e) {
            var host = document.querySelector('#compose-root > div > div');
            var canvas = host && host.shadowRoot && host.shadowRoot.querySelector('canvas');
            if (!canvas) return;
            e.preventDefault();
            canvas.dispatchEvent(new WheelEvent('wheel', {
                deltaX: e.deltaX, deltaY: e.deltaY, deltaMode: e.deltaMode, clientX: e.clientX, clientY: e.clientY,
                screenX: e.screenX, screenY: e.screenY, ctrlKey: e.ctrlKey, shiftKey: e.shiftKey, bubbles: true, cancelable: true
            }));
        }, { passive: false });
        return a;
    })()""",
)

internal fun setLink(a: HTMLAnchorElement, href: String, label: String): Unit = js(
    "(function () { a.setAttribute('href', href); a.setAttribute('aria-label', label); })()",
)

/** A real form, so password managers see it: inputs with autocomplete, a submit button, labels. */
internal fun createSignInForm(serverUrl: String, onSubmit: (String, String, String) -> Unit): HTMLFormElement = js(
    """(function () {
        var form = document.createElement('form');
        form.method = 'post';
        form.className = 'c-form';
        form.innerHTML =
            '<label>Server<input type="url" name="server" autocomplete="url" required placeholder="https://jellyfin.example.org"></label>' +
            '<label>Username<input type="text" name="username" autocomplete="username" required></label>' +
            '<label>Password<input type="password" name="password" autocomplete="current-password"></label>' +
            '<button type="submit">Sign in</button>';
        form.elements.server.value = serverUrl;
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            onSubmit(form.elements.server.value, form.elements.username.value, form.elements.password.value);
        });
        return form;
    })()""",
)

internal fun setFormBusy(form: HTMLFormElement, busy: Boolean): Unit = js(
    """(function () {
        var b = form.querySelector('button');
        b.disabled = busy;
        b.textContent = busy ? 'Signing in…' : 'Sign in';
    })()""",
)

/** The player's `<video>`, behind the canvas (index.html's #video-layer). */
internal fun createLayerVideo(label: String): HTMLVideoElement = js(
    """(function () {
        var layer = document.getElementById('video-layer');
        var v = document.createElement('video');
        v.setAttribute('aria-label', label);
        layer.appendChild(v);
        layer.style.display = 'block';
        return v;
    })()""",
)

internal fun removeLayerVideo(video: HTMLVideoElement): Unit = js(
    "(function () { video.remove(); document.getElementById('video-layer').style.display = 'none'; })()",
)

/** Fullscreen covers the whole page: the video layer and the canvas with our controls. */
internal fun pageElement(): HTMLElement = js("document.documentElement")

internal fun nowMillis(): Double = js("performance.now()")
