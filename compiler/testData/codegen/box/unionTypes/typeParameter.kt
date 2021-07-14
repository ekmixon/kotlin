// IGNORE_BACKEND: JVM, JVM_IR
// IGNORE_LIGHT_ANALYSIS

fun <T> foo(x: T | Int): T | Number = x

fun box() = foo<Boolean | String>("OK")