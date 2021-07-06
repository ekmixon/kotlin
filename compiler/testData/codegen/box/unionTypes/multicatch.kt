// TARGET_BACKEND: JVM_IR
// IGNORE_BACKEND: JVM_IR

fun box(): String {
    try {
        throw NullPointerException()
    } catch (e: IllegalArgumentException | NullPointerException) {
        return "OK"
    } finally {

    }

    return "fail"
}