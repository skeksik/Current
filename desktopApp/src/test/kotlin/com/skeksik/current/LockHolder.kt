package com.skeksik.current

import java.nio.file.Path

/** Run in a child JVM by [FileLockSingleInstanceTest]: claims the folder in args[0], then reports on stdout until it is killed. */
fun main(args: Array<String>) {
    val claim = FileLockSingleInstance(Path.of(args[0])).claim {
        println("activated")
        System.out.flush()
    }
    println(if (claim is Claim.Primary) "primary" else "not primary")
    System.out.flush()
    Thread.sleep(Long.MAX_VALUE)
}
