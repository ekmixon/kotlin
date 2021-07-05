// FIR_IDENTICAL
// IGNORE_BACKEND: JVM_IR, JS_IR

typealias Foo = Int | String

fun foo(a: (String | Boolean) -> Float | Int): Boolean | String = "foo"

fun bar() {
    try {

    } catch (e: IllegalArgumentException | NullPointerException) {
        e.printStackTrace()
    } finally {

    }
}