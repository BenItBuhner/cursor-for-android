package com.cursorforandroid.data.api

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Every Connect call this app makes to the account service goes to the path the desktop would make it on: `/` +
 * the service's `typeName` + `/` + the method's `name` as the descriptors of Cursor 3.21.16 declare them (see
 * `fixtures/desktop-3.21.16-rpc-methods.tsv`) — PascalCase `name`, never the lowerCamel `localName` a client method
 * is called by. Read off the sources, so a method literal that slips in with the wrong casing, a service that is not
 * the method's, or a method the desktop does not declare fails here rather than on a phone: on 2026-09-21 the
 * refusal "getLatestAgentConversationState has been removed" raised exactly that question, and the answer had to
 * come from the bundle.
 *
 * Also pinned: the removed methods are not called any more, and the ones the app depends on are the ones the
 * desktop itself calls at runtime.
 */
class ExtendedRpcPathsTest {

    private val sources = File(System.getProperty("user.dir"), "src/main/java/com/cursorforandroid/data/api").normalize()
    private val fixture = File(System.getProperty("user.dir"), "src/test/resources/fixtures/desktop-3.21.16-rpc-methods.tsv").normalize()

    /** `typeName/name` → (localName, kind) for every method of the services the app calls. */
    private val desktop: Map<String, Pair<String, String>> by lazy {
        fixture.readLines().filterNot { it.isBlank() || it.startsWith("#") }.associate { line ->
            val (path, local, kind) = line.split('\t')
            path to (local to kind)
        }
    }

    /** One call the app makes: the service constant's value, the method literal, and where. */
    private data class Call(val service: String, val method: String, val at: String)

    /**
     * The calls, read off the sources: the method literals passed to `unaryWithSession(SERVICE, "Method", …)`,
     * `serverStreamWithSession(SERVICE, "Method", …)`, `call("Method", …)` (whose service is the file's `SERVICE`
     * constant or the `BackgroundComposerApi.SERVICE` it delegates to), and the reader's `METHOD` constant.
     */
    private fun calls(): List<Call> {
        val files = sources.listFiles { f -> f.extension == "kt" }.orEmpty().sortedBy { it.name }
        assertWithMessage("the api sources at $sources").that(files).isNotEmpty()
        val constants = HashMap<String, String>()
        files.forEach { file ->
            val text = file.readText()
            Regex("""const val (\w*SERVICE\w*)\s*=\s*"([^"]+)"""").findAll(text).forEach { m -> constants["${file.nameWithoutExtension}.${m.groupValues[1]}"] = m.groupValues[2] }
        }
        val out = ArrayList<Call>()
        files.forEach { file ->
            val text = file.readText()
            val name = file.nameWithoutExtension
            fun service(ref: String): String? = when {
                ref.startsWith("\"") -> ref.trim('"')
                '.' in ref -> constants[ref]
                else -> constants["$name.$ref"] ?: constants.entries.firstOrNull { it.key.endsWith(".$ref") && it.key.startsWith(name) }?.value
            }
            // Explicit service and method: unaryWithSession(SERVICE, "Method" / serverStreamWithSession(SERVICE, "Method"
            Regex("""(?:unaryWithSession|serverStreamWithSession|serverStream|unary)\(\s*([\w.]+|"[^"]+"),\s*"([A-Za-z]+)"""").findAll(text).forEach { m ->
                service(m.groupValues[1])?.let { out += Call(it, m.groupValues[2], "$name: ${m.groupValues[0].take(60)}") }
            }
            // A file's own call("Method", …) helper: the service is the one its helper names.
            val helperService = Regex("""(?:unaryWithSession|unary)\(\s*([\w.]+),\s*method""").find(text)?.groupValues?.get(1)?.let(::service)
            if (helperService != null) {
                Regex("""\bcall\("([A-Za-z]+)"""").findAll(text).forEach { m -> out += Call(helperService, m.groupValues[1], "$name: call(\"${m.groupValues[1]}\")") }
            }
            // The state reader names its method as a constant.
            Regex("""const val METHOD = "([A-Za-z]+)"""").find(text)?.let { m -> constants["$name.SERVICE"]?.let { svc -> out += Call(svc, m.groupValues[1], "$name: METHOD") } }
        }
        return out
    }

    @Test
    fun `every account RPC the app builds is the desktop's typeName slash name, PascalCase, on the method's own service`() {
        val calls = calls()
        assertThat(calls.map { it.method }).containsAtLeast("StreamConversation", "GetBlobForAgentKV", "ListPendingFollowups", "AddAsyncFollowupBackgroundComposer", "GetMe")
        calls.forEach { call ->
            val path = ConnectRpc.path(call.service, call.method)
            assertWithMessage("${call.at}: a method literal must be the descriptor's PascalCase name").that(call.method).matches("[A-Z][A-Za-z0-9]*")
            val known = desktop[path.removePrefix("/")]
            assertWithMessage("${call.at}: $path is not a method the desktop's 3.21.16 descriptors declare on that service").that(known).isNotNull()
            // The lowerCamel client-method name is not the wire name: a path built from it would not route.
            assertWithMessage("${call.at}: $path must not be the localName").that(call.method).isNotEqualTo(known!!.first)
            // And the request the app would send goes to exactly that path.
            val request = ConnectRpc.request("https://api2.cursor.sh", call.service, call.method, "token", "{}")
            assertThat(request.url.encodedPath).isEqualTo(path)
            assertThat(request.header("Connect-Protocol-Version")).isEqualTo("1")
        }
    }

    @Test
    fun `a streaming call is built on the same path, enveloped, as connect+json`() {
        val request = ConnectRpc.streamRequest("https://api2.cursor.sh/", "aiserver.v1.BackgroundComposerService", "StreamConversation", "token", """{"bcId":"bc-1"}""")
        assertThat(request.url.toString()).isEqualTo("https://api2.cursor.sh/aiserver.v1.BackgroundComposerService/StreamConversation")
        assertThat(request.body!!.contentType().toString()).isEqualTo("application/connect+json")
        assertThat(request.header("Connect-Protocol-Version")).isEqualTo("1")
        val buffer = okio.Buffer().also { request.body!!.writeTo(it) }
        val bytes = buffer.readByteArray()
        assertThat(bytes[0].toInt()).isEqualTo(0)
        val length = ((bytes[1].toInt() and 0xFF) shl 24) or ((bytes[2].toInt() and 0xFF) shl 16) or ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        assertThat(length).isEqualTo(bytes.size - 5)
        assertThat(String(bytes, 5, length, Charsets.UTF_8)).isEqualTo("""{"bcId":"bc-1"}""")
        assertThat(desktop.getValue("aiserver.v1.BackgroundComposerService/StreamConversation").second).isEqualTo("ServerStreaming")
    }

    @Test
    fun `the methods the server has removed are not called, and the record read is the one the desktop makes`() {
        val methods = calls().map { it.method }.toSet()
        // Removed 2026-09-20 ("FetchBackgroundComposer has been removed") — kept only behind `readsTurns = false` for the captured shapes; and
        // removed 2026-09-21 ("getLatestAgentConversationState has been removed"), which the desktop never called.
        assertThat(methods).doesNotContain("GetLatestAgentConversationState")
        assertThat(methods).contains("StreamConversation")
        assertThat(methods).contains("GetBlobForAgentKV")
    }
}
