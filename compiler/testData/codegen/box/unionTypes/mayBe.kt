// IGNORE_BACKEND: JVM, JVM_IR
// IGNORE_LIGHT_ANALYSIS

object None

fun calc(f: () -> Unit): Unit | None {
    try {
        f()
    } catch (e: Throwable) {
        return None
    }
}

fun box(): String {
    if (calc { 1 / 0 } is None)
        return "OK"

    return "Fail"
}