package com.tverona.scpanywhere.pathhandlers

/**
 * An in-memory resource, used by the in-memory path handler
 */
data class InMemoryResourceEntry(val path: String, val mimeType: String, val body: String)
