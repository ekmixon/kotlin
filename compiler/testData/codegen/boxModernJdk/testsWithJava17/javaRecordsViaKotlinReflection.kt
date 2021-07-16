// ATTACH_DEBUGGER
// WITH_REFLECT
// ISSUE: KT-47760

// FILE: MyRecord.java
public record MyRecord(String stringField) {
    public String getStringField() {
        return "OK";
    }
}

// FILE: main.kt
import kotlin.reflect.full.*

fun box(): String {
    val obj = MyRecord("Hello")

    val function = MyRecord::class.functions.single { it.name == "stringField" }
    val functionResult = function.call(obj)
    if (functionResult != "Hello") return "Function stringField() returned $functionResult, expected \"Hello\""

    val property = MyRecord::class.memberProperties.single { it.name == "stringField" }
    val propertyResult = property.getter.call(obj)
    if (propertyResult != "OK") return "Property stringField() returned $propertyResult, expected \"OK\""

    return "OK"
}
