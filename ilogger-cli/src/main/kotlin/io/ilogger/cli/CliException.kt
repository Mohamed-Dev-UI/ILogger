package io.ilogger.cli

/** A runtime failure that should end the command with exit code 1. */
internal class CliException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Invalid command-line usage; ends the command with exit code 2. */
internal class UsageException(message: String) : Exception(message)
