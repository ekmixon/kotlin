// WITH_RUNTIME
// WITH_COROUTINES
import helpers.*
import kotlin.coroutines.*
import kotlin.properties.Delegates

class Pipe {
    var value = 0
    suspend fun send(value: Int) {
        this.value = value
    }
}

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation)
}

val pipe = Pipe()

var mode by Delegates.observable(0) {_, _, value -> builder { pipe.send(value) }}

fun box() : String {
    mode = 42
    if (pipe.value != 42) return "FAIL"
    return "OK"
}
