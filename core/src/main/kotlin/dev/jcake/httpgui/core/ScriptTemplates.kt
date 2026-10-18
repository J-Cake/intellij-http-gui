package dev.jcake.httpgui.core

/** Starting points for handler scripts, used for new requests and as editor placeholders. */
object ScriptTemplates {
    val RESPONSE_HANDLER = """
        client.test("Request executed successfully", function () {
            client.assert(response.status === 200, "Response status is not 200");
        });
    """.trimIndent()

    val PRE_REQUEST = """
        request.variables.set("timestamp", Date.now().toString());
    """.trimIndent()
}
