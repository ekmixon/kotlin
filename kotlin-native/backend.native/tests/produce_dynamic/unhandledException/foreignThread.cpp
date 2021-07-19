/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

#include "testlib_api.h"

#include <iostream>
#include <exception>
#include <future>

int main(int argc, char** argv) {
    // Run Kotlin code in a separate thread and then try get the result
    // in the main thread which is not registered in the Kotlin runtime.
    std::async(std::launch::async, []() {
        // The reverse interop machinery will catch the exception on the interop border and terminate the program.
        testlib_symbols()->kotlin.root.setHookAndThrow();
    }).get();
}