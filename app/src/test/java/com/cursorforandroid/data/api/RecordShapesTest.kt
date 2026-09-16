package com.cursorforandroid.data.api

import com.cursorforandroid.fixtures.CoordinatorFixtures
import com.cursorforandroid.fixtures.ShapeFixtures
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * The shape dump's grammar (see [RecordShapes]): every key with its value's type, strings as lengths, numbers as
 * `num`, enum-like tokens shown only under the keys that name a kind of thing; and the parser's own account of each
 * step of the record — the branch that consumed it, where a call's id, name and arguments were read from.
 */
class RecordShapesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun shape(text: String): String = RecordShapes.describe(json.parseToJsonElement(text))

    @Test
    fun `strings are lengths, numbers are num, booleans and nulls are themselves, arrays show their size and first element`() {
        assertThat(shape("""{"text":"So where we at rn","n":42,"f":1.5,"ok":true,"none":null,"empty":[],"list":[{"a":"x"},{"a":"yy"}]}"""))
            .isEqualTo("{text:str(17),n:num,f:num,ok:true,none:null,empty:[0],list:[2:{a:str(1)}]}")
    }

    @Test
    fun `the tool's name and enum are shown on a call, an agent mode and a status type anywhere, and nothing else is`() {
        val call = """{"toolCall":{"tool":"CLIENT_SIDE_TOOL_V2_UNSPECIFIED","toolCallId":"toolu_01SendA","name":"SendMessage","rawArgs":"{\"text\":{\"content\":\"hi\"}}","isStreaming":true,"modelCallId":"m1"}}"""
        assertThat(shape(call)).isEqualTo("{toolCall:{tool:CLIENT_SIDE_TOOL_V2_UNSPECIFIED,toolCallId:str(13),name:SendMessage,rawArgs:str(25),isStreaming:true,modelCallId:str(2)}}")
        assertThat(shape("""{"humanMessage":{"text":"Start this Project.","agentMode":"AGENT_MODE_PROJECT"}}""")).isEqualTo("{humanMessage:{text:str(19),agentMode:AGENT_MODE_PROJECT}}")
        assertThat(shape("""{"status":{"type":"STATUS_TYPE_GENERIC","message":"finished","isComplete":true}}""")).isEqualTo("{status:{type:STATUS_TYPE_GENERIC,message:str(8),isComplete:true}}")
        // A name deeper down is a worker's or a file's, and a name with a space in it is prose: lengths, both.
        assertThat(shape("""{"finalToolResult":{"toolCallId":"t","result":{"workers":[{"name":"Kitchen","bc_id":"bc-1"}]}}}"""))
            .isEqualTo("{finalToolResult:{toolCallId:str(1),result:{workers:[1:{name:str(7),bc_id:str(4)}]}}}")
        assertThat(shape("""{"toolCall":{"name":"Render kitchen v2 shard","toolCallId":"t"}}""")).isEqualTo("{toolCall:{name:str(23),toolCallId:str(1)}}")
        // A key the record has that this build does not know is in the shape all the same: that is what the dump is for.
        assertThat(shape("""{"sendMessage":{"text":{"content":"PR #215 is merged."},"messageId":"m-1"}}""")).isEqualTo("{sendMessage:{text:{content:str(18)},messageId:str(3)}}")
    }

    @Test
    fun `an object with too many keys and a nesting too deep are cut, and say so`() {
        val wide = (1..40).joinToString(",", "{", "}") { "\"k$it\":$it" }
        assertThat(shape(wide)).endsWith(",k32:num,+8}")
        val deep = "{\"a\":".repeat(8) + "1" + "}".repeat(8)
        assertThat(shape(deep)).isEqualTo("{a:{a:{a:{a:{a:{a:{…}}}}}}}")
    }

    @Test
    fun `every response of the record fixture reads back from its shape into the same shape`() {
        val responses = CoordinatorFixtures.json("record_coordinator_pages.json").getValue("responses").jsonArray
        for (response in responses) {
            val described = RecordShapes.describe(response.jsonObject)
            assertThat(RecordShapes.describe(ShapeFixtures.skeleton(described))).isEqualTo(described)
        }
    }

    @Test
    fun `each step names the branch that read it and where a call's id, name and arguments came from`() {
        fun branch(text: String): String = HeadlessConversationApi.parseStep(json.parseToJsonElement(text).jsonObject, 7).shape!!.branch
        assertThat(branch("""{"userMessage":{"text":"Hi"}}""")).isEqualTo("user_message")
        assertThat(branch("""{"humanMessage":{"text":"Hi","agentMode":"AGENT_MODE_PROJECT"}}""")).isEqualTo("human_message")
        assertThat(branch("""{"text":"Told Bennett."}""")).isEqualTo("text")
        assertThat(branch("""{"thinking":{"text":"Hm."}}""")).isEqualTo("thinking")
        assertThat(branch("""{"toolCall":{"toolCallId":"t","name":"SendMessage","rawArgs":"{\"text\":{\"content\":\"hi\"}}"}}""")).isEqualTo("tool_call[id=toolCallId name=name args=json]")
        assertThat(branch("""{"toolCall":{"modelCallId":"m","tool":"CLIENT_SIDE_TOOL_V2_SEND_TO_USER","rawArgs":"{\"message\":\"hi"}}""")).isEqualTo("tool_call[id=modelCallId name=tool args=piece]")
        assertThat(branch("""{"toolCall":{"toolCallId":"t","readFileV2Params":{"relativeWorkspacePath":"A.kt"}}}""")).isEqualTo("tool_call[id=toolCallId name=params args=params]")
        assertThat(branch("""{"toolCall":{"toolCallId":"t","tool":"CLIENT_SIDE_TOOL_V2_UNSPECIFIED"}}""")).isEqualTo("tool_call[id=toolCallId name=blank args=none]")
        assertThat(branch("""{"toolCall":{"name":"SendMessage","rawArgs":"{}"}}""")).isEqualTo("tool_call[dropped:no-id]")
        assertThat(branch("""{"streamedBackToolCall":{"toolCallId":"t","name":"SendMessage","rawArgs":"{\"text\":{\"content\":\"hi\"}}"}}""")).isEqualTo("streamed_back_tool_call[id=toolCallId name=name args=json]")
        assertThat(branch("""{"finalToolResult":{"toolCallId":"t","result":{}}}""")).isEqualTo("final_tool_result")
        assertThat(branch("""{"finalToolResult":{"toolCallId":"","result":{}}}""")).isEqualTo("final_tool_result[dropped:no-id]")
        assertThat(branch("""{"error":{"message":"Model provider overloaded."}}""")).isEqualTo("error")
        assertThat(branch("""{"status":{"type":"STATUS_TYPE_GENERIC","isComplete":true}}""")).isEqualTo("blank(status)")
        assertThat(branch("""{"text":"","isMessageDone":true}""")).isEqualTo("blank(message_done)")
        assertThat(branch("""{"somethingNew":{"content":"?"}}""")).isEqualTo("blank")
        // The step carries its index and its shape beside the branch.
        val step = HeadlessConversationApi.parseStep(json.parseToJsonElement("""{"error":{"message":"Model provider overloaded."}}""").jsonObject, 4041)
        assertThat(step.error).isEqualTo("Model provider overloaded.")
        assertThat(step.shape!!.index).isEqualTo(4041)
        assertThat(step.shape!!.keys).isEqualTo("{error:{message:str(26)}}")
    }
}
