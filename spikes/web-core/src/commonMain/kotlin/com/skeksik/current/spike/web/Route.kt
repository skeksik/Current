package com.skeksik.current.spike.web

/** App URLs. Every page has its own path, so links work with middle-click, new tabs and copy link. */
sealed interface Route {
    val path: String

    data object Home : Route {
        override val path = "/"
    }

    data object SignIn : Route {
        override val path = "/sign-in"
    }

    data class Library(val id: String) : Route {
        override val path get() = "/library/$id"
    }

    data class Item(val id: String) : Route {
        override val path get() = "/items/$id"
    }

    data class Player(val id: String) : Route {
        override val path get() = "/items/$id/play"
    }

    data class NotFound(override val path: String) : Route

    companion object {
        fun parse(path: String): Route {
            val parts = path.substringBefore('?').substringBefore('#').split('/').filter { it.isNotEmpty() }
            return when {
                parts.isEmpty() -> Home
                parts == listOf("sign-in") -> SignIn
                parts.size == 2 && parts[0] == "library" && isId(parts[1]) -> Library(parts[1])
                parts.size == 2 && parts[0] == "items" && isId(parts[1]) -> Item(parts[1])
                parts.size == 3 && parts[0] == "items" && isId(parts[1]) && parts[2] == "play" -> Player(parts[1])
                else -> NotFound(path)
            }
        }

        /** Jellyfin ids: 32 lowercase hex characters without dashes. */
        private fun isId(s: String) = s.length == 32 && s.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/** A plain primary click is handled by the app; anything else (middle-click, Ctrl/Cmd/Shift/Alt) is left to the browser. */
fun isPlainClick(button: Int, ctrl: Boolean, meta: Boolean, shift: Boolean, alt: Boolean): Boolean =
    button == 0 && !ctrl && !meta && !shift && !alt
