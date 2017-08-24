# JSObject_gets_ScriptFunction
Reproduces issue where a JSObject is passed a ScriptFunction instead of a ScriptObjectMirror

On java 8u144 this demonstrates an issue where a JSObject is passed a ScriptFunction instead of a ScriptObjectMirror.

This happens when running tests using the jasmine test framework after setting up the environment similar to how it would be setup in another javascript environment.

For the first 15 iterations of the test the JSObject is passed a ScriptObjectMirror and everything functions as expected.  On the 16th iteration the JSObject gets a ScriptFunction instead of a ScriptObjectMirror and an exception is thrown.
