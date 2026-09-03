package com.example

import com.example.data.model.McpRequest
import com.example.data.model.NodeBounds
import com.example.data.model.ScreenDump
import com.example.data.model.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

  @Test
  fun testMcpRequestParsing() {
    val json = """{
      "jsonrpc": "2.0",
      "id": 100,
      "method": "tools/call",
      "params": {
        "name": "scroll_and_read",
        "arguments": { "scroll_count": 3 }
      }
    }"""

    val req = McpRequest.fromJson(json)
    assertEquals("2.0", req.jsonrpc)
    assertEquals(100, req.id)
    assertEquals("tools/call", req.method)
    assertEquals("scroll_and_read", req.params.optString("name"))
  }

  @Test
  fun testScreenDumpJsonFormatting() {
    val node = UiNode(
      id = "root/0",
      className = "android.widget.TextView",
      text = "Bonjour le monde",
      bounds = NodeBounds(0, 0, 1080, 200)
    )
    val dump = ScreenDump(
      timestamp = 1725000000000L,
      packageName = "com.test.app",
      appName = "TestApp",
      scrollPasses = 2,
      extractedTexts = listOf("Bonjour le monde"),
      rootNode = node
    )

    val jsonStr = dump.toFormattedJsonString(2)
    assertNotNull(jsonStr)
    assertTrue(jsonStr.contains("Bonjour le monde"))
    assertTrue(jsonStr.contains("com.test.app"))
  }
}
