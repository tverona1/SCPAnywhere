package com.tverona.scpanywhere.downloader

import okio.IOException

/**
 * Exception for rate limit exceeded transient error
 */
class RateLimitExceededException(message: String) : IOException(message)