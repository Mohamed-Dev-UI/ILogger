package io.ilogger.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(Cli.run(args, System.out, System.err))
}
