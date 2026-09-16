package com.cursorforandroid.data.repo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A coordinator's message read out of arguments that do not parse (see [MessageRecovery]): the pieces of a streamed
 * `SendMessage` the record cut short, closed as JSON; text around a JSON-like core, by its `content` key; plain
 * text, as itself; and nothing from arguments that hold no message.
 */
class MessageRecoveryTest {

    @Test
    fun `whole arguments read as JSON under any of the message tool's shapes`() {
        assertThat(MessageRecovery.recover("""{"text":{"content":"PR #215 is merged."}}""")).isEqualTo(MessageRecovery.Recovered("PR #215 is merged.", "json"))
        assertThat(MessageRecovery.recover("""{"message":"PR #215 is merged."}""")).isEqualTo(MessageRecovery.Recovered("PR #215 is merged.", "json"))
        assertThat(MessageRecovery.recover("""{"attachment":{"url":"https://x.test/a.png","alt":"Shot"}}""")?.method).isEqualTo("json")
    }

    @Test
    fun `pieces cut short are closed and read, whichever way the cut fell`() {
        val cutInString = """{"text":{"content":"All four shards rendered and their PRs are me"""
        assertThat(MessageRecovery.recover(cutInString)).isEqualTo(MessageRecovery.Recovered("All four shards rendered and their PRs are me", "repaired"))
        val cutAfterString = """{"text":{"content":"All four shards rendered.""""
        assertThat(MessageRecovery.recover(cutAfterString)).isEqualTo(MessageRecovery.Recovered("All four shards rendered.", "repaired"))
        val cutAfterComma = """{"text":{"content":"All four shards rendered.","""
        assertThat(MessageRecovery.recover(cutAfterComma)).isEqualTo(MessageRecovery.Recovered("All four shards rendered.", "repaired"))
        val cutInEscape = """{"text":{"content":"He said \"go\" and \"""
        assertThat(MessageRecovery.recover(cutInEscape)).isEqualTo(MessageRecovery.Recovered("He said \"go\" and", "repaired"))
        val withNewlines = """{"text":{"content":"Line one.\n\nLine two, cut he"""
        assertThat(MessageRecovery.recover(withNewlines)!!.text).isEqualTo("Line one.\n\nLine two, cut he")
        // Closing alone gives nothing to read: no body was written yet.
        assertThat(MessageRecovery.recover("""{"text":{"content":""")).isNull()
        assertThat(MessageRecovery.recover("""{"text":{""")).isNull()
    }

    @Test
    fun `text around a JSON-like core is read by its content key, then message, then text`() {
        assertThat(MessageRecovery.recover("""args: {"text":{"content":"Shards are queued."}} trailing garbage""")).isEqualTo(MessageRecovery.Recovered("Shards are queued.", "regex"))
        assertThat(MessageRecovery.recover("""<call>{"message":"Shards are queued.\nMore soon."}</call>""")).isEqualTo(MessageRecovery.Recovered("Shards are queued.\nMore soon.", "regex"))
        assertThat(MessageRecovery.recover("""x {"text":"Plain string text, not an object"} y""")).isEqualTo(MessageRecovery.Recovered("Plain string text, not an object", "regex"))
        // The content key wins over a message key that comes first.
        assertThat(MessageRecovery.recover("""x {"message":{"case":"text","value":{"content":"The body."}}} y""")!!.text).isEqualTo("The body.")
    }

    @Test
    fun `text with no JSON in it is the message itself, and JSON with no message in it is nothing`() {
        assertThat(MessageRecovery.recover("The phone worker has the Fold8 on its list.")).isEqualTo(MessageRecovery.Recovered("The phone worker has the Fold8 on its list.", "plain"))
        assertThat(MessageRecovery.recover("   ")).isNull()
        assertThat(MessageRecovery.recover("""{"agent_id":"bc-1","delivery":"immediate"}""")).isNull()
        assertThat(MessageRecovery.recover("""{"agent_id":"bc-1","del""")).isNull()
    }

    @Test
    fun `closing shuts strings and brackets in order and drops a dangling comma`() {
        assertThat(MessageRecovery.close("""{"a":[1,2""")).isEqualTo("""{"a":[1,2]}""")
        assertThat(MessageRecovery.close("""{"a":{"b":"x""")).isEqualTo("""{"a":{"b":"x"}}""")
        assertThat(MessageRecovery.close("""{"a":"x",""")).isEqualTo("""{"a":"x"}""")
        assertThat(MessageRecovery.close("""{"a":""")).isEqualTo("""{"a":""}""")
        // A string cut right after an escaped quote keeps it and is closed after it.
        assertThat(MessageRecovery.close("{\"a\":\"x\\\"")).isEqualTo("{\"a\":\"x\\\"\"}")
        // A lone backslash the cut left behind is dropped rather than escaping the closing quote.
        assertThat(MessageRecovery.close("{\"a\":\"x\\")).isEqualTo("{\"a\":\"x\"}")
    }
}
