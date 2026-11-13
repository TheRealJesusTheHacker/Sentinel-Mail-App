package net.sentinelsafeguard.mailapp.ui

/**
 * One file handed over by the platform file picker (desktop port
 * seam, 2026-11-13): Android reads it through the document
 * resolver, desktop reads it from the filesystem — the compose
 * screen applies the same size cap and model wiring to the bytes
 * either way.
 */
data class PickedAttachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PickedAttachment && name == other.name &&
            mimeType == other.mimeType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        31 * (31 * name.hashCode() + mimeType.hashCode()) + bytes.contentHashCode()
}
