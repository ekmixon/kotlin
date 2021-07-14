// IGNORE_BACKEND: JVM, JVM_IR
// IGNORE_LIGHT_ANALYSIS

fun foo(): Int | String = "OK"

fun bar(x: String | Number | Boolean) = x

fun box() = bar(foo())