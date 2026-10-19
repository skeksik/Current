package com.skeksik.current.core.model

/** The role of an image. Open like [MediaKind]: unknown types from a server keep their value. */
data class ImageType(val value: String) {
    init {
        require(value.isNotBlank()) { "ImageType must not be blank" }
    }

    val isKnown: Boolean get() = this in known

    companion object {
        /** The main image: a poster for movies and series, a still for episodes, a photo for people. */
        val primary = ImageType("primary")
        val backdrop = ImageType("backdrop")
        /** The title as an image, usually with transparency. */
        val logo = ImageType("logo")
        /** A landscape image for wide cards. */
        val thumb = ImageType("thumb")
        val banner = ImageType("banner")

        val known: List<ImageType> = listOf(primary, backdrop, logo, thumb, banner)
    }
}

/**
 * Width to height, kept as the reduced fraction, so a 1000×1500 image and a 2:3 poster are equal. UIs use it to reserve a card's
 * space before the image loads.
 */
class AspectRatio private constructor(val width: Int, val height: Int) {
    /** Width divided by height: below 1 is portrait, above 1 landscape. */
    val value: Double get() = width.toDouble() / height

    fun heightFor(width: Double): Double = width * height / this.width

    fun widthFor(height: Double): Double = height * width / this.height

    override fun equals(other: Any?): Boolean = other is AspectRatio && width == other.width && height == other.height

    override fun hashCode(): Int = 31 * width + height

    override fun toString(): String = "$width:$height"

    companion object {
        /** From any width and height, such as an image's pixel size; both must be positive. */
        fun of(width: Int, height: Int): AspectRatio {
            require(width > 0 && height > 0) { "AspectRatio needs a positive width and height, got $width×$height" }
            val divisor = gcd(width, height)
            return AspectRatio(width / divisor, height / divisor)
        }

        val poster = of(2, 3)
        val wide = of(16, 9)
        val square = of(1, 1)

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    }
}

/**
 * One image of an item. [url] is built by the data layer from the configured server (core knows no server); it must not carry
 * credentials. [aspectRatio] is null when the server does not report it; a UI then falls back to what [type] usually is.
 * [blurHash] is a placeholder to show while the image loads.
 */
data class Artwork(
    val type: ImageType,
    val url: String,
    val aspectRatio: AspectRatio? = null,
    val blurHash: String? = null,
)
