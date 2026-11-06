package com.vibecoderspace.chrootengine

/** Failures that callers can present to the user without parsing messages. */
sealed class InstallException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    class Network(message: String, cause: Throwable? = null) :
        InstallException(message, cause)

    class Checksum(message: String) : InstallException(message)

    class Extraction(message: String, cause: Throwable? = null) :
        InstallException(message, cause)

    class Validation(message: String) : InstallException(message)

    class InsufficientSpace(
        val requiredBytes: Long,
        val availableBytes: Long,
    ) : InstallException(
        "Not enough space: need $requiredBytes bytes, have $availableBytes",
    )
}
