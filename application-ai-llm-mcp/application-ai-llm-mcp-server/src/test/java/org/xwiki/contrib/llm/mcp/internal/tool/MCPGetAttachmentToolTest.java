/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.llm.mcp.internal.tool;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPSourceText;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.web.XWikiURLFactory;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.mcp.internal.tool.MCPToolTestUtils.textOf;

/**
 * Tests for {@link MCPGetAttachmentTool}.
 *
 * @version $Id$
 */
@OldcoreTest
class MCPGetAttachmentToolTest extends AbstractMCPToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String FILENAME_KEY = "filename";

    private static final String OFFSET_KEY = "offset";

    private static final String METADATA_KEY = "metadata";

    private static final String REF = "Sandbox.WebHome";

    private static final String CANONICAL = "xwiki:Sandbox.WebHome";

    private static final String AUTHOR_CANONICAL = "xwiki:XWiki.PaulPantiru";

    private static final String NOTES_TXT = "notes.txt";

    private static final String TEXT_PLAIN = "text/plain";

    private static final String NOTES_CONTENT = "Line one\nLine two";

    private static final String DOWNLOAD_URL = "https://wiki.example/bin/download/Sandbox/WebHome/notes.txt";

    private static final DocumentReference DOC_REF = new DocumentReference("xwiki", "Sandbox", "WebHome");

    private static final DocumentReference AUTHOR_REF = new DocumentReference("xwiki", "XWiki", "PaulPantiru");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPGetAttachmentTool tool;

    @MockComponent
    private MCPDocumentAccess documentAccess;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    private EntityReferenceSerializer<String> serializer;

    @MockComponent
    private MCPWikiReach wikiReach;

    private MockitoOldcore oldcore;

    @BeforeEach
    void setUp(MockitoOldcore mockitoOldcore) throws Exception
    {
        this.oldcore = mockitoOldcore;
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW))).thenReturn(DOC_REF);
        lenient().when(this.serializer.serialize(DOC_REF)).thenReturn(CANONICAL);
        lenient().when(this.serializer.serialize(AUTHOR_REF)).thenReturn(AUTHOR_CANONICAL);
        lenient().when(this.wikiReach.isReachEnabled()).thenReturn(true);
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    /**
     * @param text a possibly very long tool result
     * @return its last 400 characters, for assertion messages that must not dump a giant window
     */
    private static String tailOf(String text)
    {
        return text.substring(Math.max(0, text.length() - 400));
    }

    private XWikiDocument stubDocument() throws Exception
    {
        XWikiDocument doc = new XWikiDocument(DOC_REF);
        doc.setVersion("3.2");
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(DOC_REF)).thenReturn(doc);
        return doc;
    }

    private XWikiAttachment addAttachment(XWikiDocument doc, String filename, byte[] bytes, String mimeType)
        throws Exception
    {
        XWikiAttachment attachment = new XWikiAttachment(doc, filename);
        attachment.setContent(new ByteArrayInputStream(bytes));
        attachment.setMimeType(mimeType);
        doc.setAttachment(attachment);
        return attachment;
    }

    private void stubUrlFactory() throws Exception
    {
        XWikiURLFactory urlFactory = mock(XWikiURLFactory.class);
        lenient().when(urlFactory.createAttachmentURL(any(), any(), any(), any(), any(), any(),
            any(XWikiContext.class))).thenReturn(new URL(DOWNLOAD_URL));
        this.oldcore.getXWikiContext().setURLFactory(urlFactory);
    }

    @Test
    void accessDeniedReturnsRefusalWithoutLoadingDocument() throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW)))
            .thenThrow(new MCPAccessDeniedException("Not authorized to view \"" + REF + "\"."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Not authorized"), textOf(result));
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void missingDocumentReturnsNotFound() throws Exception
    {
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(false);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("No such document: \"" + REF + "\"."), textOf(result));
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void missingAttachmentRefusalListsExistingNames() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, NOTES_TXT, NOTES_CONTENT.getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);
        addAttachment(doc, "logo.png", new byte[] {1, 2, 3}, "image/png");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "missing.txt"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Attachment \"missing.txt\" not found on \"" + REF + "\"."), text);
        // The platform's attachment list is filename-sorted, so the refusal lists names alphabetically.
        assertTrue(text.contains("Attachments on this document: logo.png, notes.txt."), text);
    }

    @Test
    void missingAttachmentRefusalSaysNoAttachmentsWhenNoneExist() throws Exception
    {
        stubDocument();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("This document has no attachments."), textOf(result));
    }

    @Test
    void filenameMatchingIsExactNotFuzzy() throws Exception
    {
        // The platform's fuzzy getAttachment would serve "report.pdf" for "report"; the tool must miss
        // instead and teach the exact name.
        XWikiDocument doc = stubDocument();
        addAttachment(doc, "report.pdf", new byte[] {1, 2, 3}, "application/pdf");

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "report"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Attachment \"report\" not found"), text);
        assertTrue(text.contains("Attachments on this document: report.pdf."), text);
    }

    @Test
    void textAttachmentReturnsFullHeaderAndContent() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment =
            addAttachment(doc, NOTES_TXT, NOTES_CONTENT.getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);
        attachment.setAuthorReference(AUTHOR_REF);
        attachment.setDate(new GregorianCalendar(2026, Calendar.AUGUST, 12, 14, 3).getTime());
        stubUrlFactory();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.startsWith("Attachment: notes.txt\n"), text);
        assertTrue(text.contains("Document: " + CANONICAL + " (version 3.2)"), text);
        assertTrue(text.contains("Mimetype: text/plain"), text);
        assertTrue(text.contains("Size: " + NOTES_CONTENT.length() + " bytes"), text);
        assertTrue(text.contains("Attachment version: 1.1"), text);
        assertTrue(text.contains("Author: " + AUTHOR_CANONICAL), text);
        assertTrue(text.contains("Date: 2026-08-12 14:03"), text);
        assertTrue(text.contains("Download: " + DOWNLOAD_URL), text);
        // The content follows the header after a blank line, in full and without a truncation note.
        assertTrue(text.endsWith("\n\n" + NOTES_CONTENT), text);
        assertFalse(text.contains("Output truncated"), text);
    }

    @Test
    void metadataOnlyReturnsHeaderWithoutOpeningTheContentStream() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment =
            spy(new XWikiAttachment(doc, NOTES_TXT));
        attachment.setContent(new ByteArrayInputStream(NOTES_CONTENT.getBytes(StandardCharsets.UTF_8)));
        attachment.setMimeType(TEXT_PLAIN);
        doc.setAttachment(attachment);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, METADATA_KEY, true));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Attachment: notes.txt"), text);
        assertFalse(text.contains("Line one"), text);
        verify(attachment, never()).getContentInputStream(any(XWikiContext.class));
    }

    @Test
    void truncatedReadCarriesContinuationOffsetThatResumesTheContent() throws Exception
    {
        XWikiDocument doc = stubDocument();
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            if (i > 0) {
                content.append('\n');
            }
            content.append(String.format("line %05d", i));
        }
        addAttachment(doc, "build.log", content.toString().getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);

        String first = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "build.log"));

        assertTrue(first.contains("\n\nline 00000\n"), "The window must start at the first line");
        Matcher matcher = Pattern.compile("Output truncated at the ~\\d+-token cap; continue with offset=(\\d+)\\.")
            .matcher(first);
        assertTrue(matcher.find(), first);
        int nextOffset = Integer.parseInt(matcher.group(1));
        String lastEmitted = String.format("line %05d", nextOffset - 1);
        String firstOmitted = String.format("line %05d", nextOffset);
        assertTrue(first.contains(lastEmitted), first);
        assertFalse(first.contains(firstOmitted), first);

        String second = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "build.log", OFFSET_KEY, nextOffset));

        // The second window resumes exactly where the first one stopped, at a line boundary.
        assertTrue(second.contains("\n\n" + firstOmitted + "\n"), second);
        assertFalse(second.substring(second.indexOf("\n\n")).contains(lastEmitted), second);
    }

    @Test
    void offsetBeyondEndReturnsBeyondTheEndMessage() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, NOTES_TXT, "one\ntwo\nthree".getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, OFFSET_KEY, 10));

        assertTrue(text.contains("Text content has only 3 lines; offset 10 is beyond the end."), text);
        assertFalse(text.contains("one\ntwo"), text);
    }

    @Test
    void binaryAttachmentReturnsDownloadPointerInsteadOfContent() throws Exception
    {
        XWikiDocument doc = stubDocument();
        byte[] bytes = new byte[2048];
        byte[] marker = "SECRETBYTES".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(marker, 0, bytes, 0, marker.length);
        addAttachment(doc, "report.pdf", bytes, "application/pdf");
        stubUrlFactory();

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "report.pdf"));

        assertNotEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Size: 2 KB (2048 bytes)"), text);
        assertTrue(text.contains("Content is application/pdf; not inlineable as text. "
            + "Use the Download URL above."), text);
        assertFalse(text.contains("SECRETBYTES"), "Binary content must never be inlined");
    }

    @Test
    void hostileFilenameIsFragmentGuardedInRefusalAndHeader() throws Exception
    {
        XWikiDocument doc = stubDocument();
        String hostile = "evil\nname" + "x".repeat(250) + ".txt";
        addAttachment(doc, hostile, "safe".getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);

        // The miss refusal lists the stored name neutralized: no forged line, cut with an ellipsis.
        String refusal = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "missing.txt"));
        assertTrue(refusal.contains("evilname"), refusal);
        assertFalse(refusal.contains("evil\nname"), refusal);
        assertTrue(refusal.contains("…"), refusal);

        // A hit on the exact stored name echoes it neutralized in the header too.
        String header = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, hostile));
        assertTrue(header.startsWith("Attachment: evilname"), header);
        assertFalse(header.contains("evil\nname"), header);
        assertTrue(header.contains("…"), header);
    }

    @Test
    void declaredCharsetDecodesTheContent() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment =
            addAttachment(doc, NOTES_TXT, "café latin".getBytes(StandardCharsets.ISO_8859_1), TEXT_PLAIN);
        attachment.setCharset("ISO-8859-1");

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertTrue(text.contains("café latin"), text);
    }

    @Test
    void oversizedSingleLineIsHardCutWithMidLineNoteAndBoundedOutput() throws Exception
    {
        XWikiDocument doc = stubDocument();
        String giant = "A".repeat(30_000);
        addAttachment(doc, "one-line.json", (giant + "\nsecond line").getBytes(StandardCharsets.UTF_8),
            "application/json");

        String first = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "one-line.json"));

        // The head of the line is emitted, hard-cut mid-line, and nothing past the cut leaks out.
        assertTrue(first.contains("AAAA"), tailOf(first));
        assertFalse(first.contains("second line"), "The window must stop inside the oversized line");
        assertTrue(first.contains("Output truncated MID-LINE at the ~"), tailOf(first));
        assertTrue(first.contains("continue with the next line at offset=1."), tailOf(first));
        // The whole result stays bounded: budget plus a small header/note slack, never the raw line size.
        assertTrue(first.length() <= MCPSourceText.MAX_OUTPUT_CHARS + 600,
            "Result length " + first.length() + " exceeds the bounded budget");

        String second = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "one-line.json", OFFSET_KEY, 1));

        // The continuation offset resumes at the line AFTER the cut one.
        assertTrue(second.endsWith("\n\nsecond line"), tailOf(second));
    }

    @Test
    void skipPhaseOverAnOversizedLineResumesAtTheRightLine() throws Exception
    {
        XWikiDocument doc = stubDocument();
        String giant = "B".repeat(30_000);
        addAttachment(doc, "log.txt",
            (giant + "\nsecond line\nthird line").getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "log.txt", OFFSET_KEY, 1));

        // Skipping the giant first line counts it as exactly one line and emits the rest.
        assertTrue(text.endsWith("\n\nsecond line\nthird line"), tailOf(text));
        assertFalse(text.contains("BBBB"), "Skipped content must not be emitted");
    }

    @Test
    void unknownCharsetFallsBackToUtf8() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment =
            addAttachment(doc, NOTES_TXT, "café fallback".getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);
        attachment.setCharset("no-such-charset");

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertTrue(text.contains("café fallback"), text);
    }

    @Test
    void existenceCheckFailureReturnsErrorAndLogsWarning() throws Exception
    {
        when(this.documentAccessBridge.exists(DOC_REF)).thenThrow(new RuntimeException("boom"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the document \"" + REF + "\"."), textOf(result));
        assertFalse(textOf(result).contains("boom"), "Root cause must not leak into the agent-facing message");
        assertTrue(this.logCapture.getMessage(0).contains("failed to check existence"),
            this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(0).contains("boom"), this.logCapture.getMessage(0));
        verify(this.documentAccessBridge, never()).getDocumentInstance(any(DocumentReference.class));
    }

    @Test
    void documentLoadFailureReturnsErrorAndLogsWarning() throws Exception
    {
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(DOC_REF)).thenThrow(new RuntimeException("boom"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the document \"" + REF + "\"."), textOf(result));
        assertFalse(textOf(result).contains("boom"), "Root cause must not leak into the agent-facing message");
        assertTrue(this.logCapture.getMessage(0).contains("failed to load"), this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(0).contains("boom"), this.logCapture.getMessage(0));
    }

    @Test
    void hostileMimetypeIsClampedInHeaderAndPointerLine() throws Exception
    {
        XWikiDocument doc = stubDocument();
        String hostile = "x".repeat(5000);
        addAttachment(doc, "blob.bin", new byte[] {1, 2}, hostile);

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "blob.bin"));

        // The stored mimetype is clamped to the fragment cap with an ellipsis, on both echo sites.
        assertTrue(text.contains("Mimetype: " + "x".repeat(200) + "…"), text.substring(0, 400));
        assertTrue(text.contains("Content is " + "x".repeat(200) + "…;"), text);
        assertFalse(text.contains("x".repeat(201)), "The mimetype must be cut at the fragment cap");
    }

    @Test
    void blankCharsetFallsBackToUtf8() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, NOTES_TXT, "café utf".getBytes(StandardCharsets.UTF_8), TEXT_PLAIN);

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertTrue(text.contains("café utf"), text);
    }

    @Test
    void emptyTextAttachmentSaysNoContent() throws Exception
    {
        XWikiDocument doc = stubDocument();
        addAttachment(doc, NOTES_TXT, new byte[0], TEXT_PLAIN);

        String text = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertTrue(text.contains("Attachment has no content."), text);
    }

    @Test
    void negativeOffsetReturnsError() throws Exception
    {
        stubDocument();

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, OFFSET_KEY, -1));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Error: 'offset' must be >= 0."), textOf(result));
    }

    @Test
    void contentReadFailureReturnsErrorAndLogsWarning() throws Exception
    {
        XWikiDocument doc = stubDocument();
        XWikiAttachment attachment = spy(new XWikiAttachment(doc, NOTES_TXT));
        attachment.setMimeType(TEXT_PLAIN);
        doc.setAttachment(attachment);
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE,
            XWikiException.ERROR_XWIKI_STORE_HIBERNATE_LOADING_ATTACHMENT, "boom"))
            .when(attachment).getContentInputStream(any(XWikiContext.class));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Could not read the attachment content."), textOf(result));
        assertFalse(textOf(result).contains("boom"), "Root cause must not leak into the agent-facing message");
        assertTrue(this.logCapture.getMessage(0).contains("failed to read the content"),
            this.logCapture.getMessage(0));
        assertTrue(this.logCapture.getMessage(0).contains("boom"), this.logCapture.getMessage(0));
    }

    @Test
    void schemaDeclaresParamsInImportanceOrderWithReferenceAndFilenameRequired()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();
        Map<?, ?> properties = (Map<?, ?>) definition.inputSchema().get("properties");
        List<Object> keys = new ArrayList<>(properties.keySet());
        assertEquals(List.of(REFERENCE_KEY, FILENAME_KEY, OFFSET_KEY, METADATA_KEY), keys);
        assertEquals(List.of(REFERENCE_KEY, FILENAME_KEY), definition.inputSchema().get("required"));
    }

    @Test
    void summaryCategoryAndWriteFlagAreDeclared()
    {
        assertEquals("Read an attachment's content or metadata from a document.", this.tool.getSummary());
        assertEquals("Search & Navigation", this.tool.getCategory());
        assertFalse(this.tool.isWrite());
        assertTrue(this.tool.isEnabled());
    }

    @Test
    void reachOnMentionsCrossWikiInReferenceDescription()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(true);

        String description = referenceDescription();

        assertTrue(description.contains("wiki-id prefix"), description);
        assertTrue(description.contains("xwiki:Sandbox.WebHome"), description);
    }

    @Test
    void reachOffDropsCrossWikiFromReferenceDescription()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(false);

        String description = referenceDescription();

        assertFalse(description.contains("wiki-id prefix"), description);
        assertFalse(description.contains("xwiki:"), description);
    }
}
