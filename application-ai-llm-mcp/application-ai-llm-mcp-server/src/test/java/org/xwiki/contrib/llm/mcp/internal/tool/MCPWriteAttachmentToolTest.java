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

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.xwiki.attachment.AttachmentAccessWrapper;
import org.xwiki.attachment.validation.AttachmentValidationException;
import org.xwiki.attachment.validation.AttachmentValidator;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPTool;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.mcp.internal.tool.MCPToolTestUtils.textOf;

/**
 * Tests for {@link MCPWriteAttachmentTool}, against the real oldcore store and real attachments so the
 * staging, validation wiring and content round-trip are exercised end to end.
 *
 * @version $Id$
 */
@OldcoreTest
class MCPWriteAttachmentToolTest extends AbstractMCPWriteToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String FILENAME_KEY = "filename";

    private static final String CONTENT_KEY = "content";

    private static final String CONTENT_BASE64_KEY = "content_base64";

    private static final String BASE_VERSION_KEY = "base_version";

    private static final String COMMENT_KEY = "comment";

    private static final String REF = "Sandbox.WebHome";

    private static final String CANONICAL = "xwiki:Sandbox.WebHome";

    private static final String NOTES_TXT = "notes.txt";

    private static final String TEXT = "line one\nline two";

    private static final String VIEW_URL = "https://wiki.example/bin/view/Sandbox/WebHome";

    private static final String DOWNLOAD_URL = "https://wiki.example/bin/download/Sandbox/WebHome/notes.txt";

    private static final String PIXEL_PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private static final DocumentReference DOC_REFERENCE = new DocumentReference("xwiki", "Sandbox", "WebHome");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPWriteAttachmentTool tool;

    @MockComponent
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @MockComponent
    private Provider<AttachmentValidator> attachmentValidatorProvider;

    private AttachmentValidator attachmentValidator;

    @BeforeEach
    void setUp(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(DOC_REFERENCE);
        lenient().when(this.serializer.serialize(any())).thenReturn(CANONICAL);
        lenient().when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);
        this.attachmentValidator = mock(AttachmentValidator.class);
        lenient().when(this.attachmentValidatorProvider.get()).thenReturn(this.attachmentValidator);

        allowSimpleSavePath(oldcore);
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    private void storeTargetDocument(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, DOC_REFERENCE, "body", null);
    }

    private XWikiDocument loadTargetDocument(MockitoOldcore oldcore) throws Exception
    {
        return loadDocument(oldcore, DOC_REFERENCE);
    }

    private String targetVersion(MockitoOldcore oldcore) throws Exception
    {
        return currentVersion(oldcore, DOC_REFERENCE);
    }

    private void stubUrlFactory(MockitoOldcore oldcore) throws Exception
    {
        XWikiURLFactory urlFactory = mock(XWikiURLFactory.class);
        lenient().when(urlFactory.createAttachmentURL(any(), any(), any(), any(), any(), any(),
            any(XWikiContext.class))).thenReturn(new URL(DOWNLOAD_URL));
        oldcore.getXWikiContext().setURLFactory(urlFactory);
    }

    private byte[] storedBytes(MockitoOldcore oldcore, String filename) throws Exception
    {
        XWikiAttachment attachment = loadTargetDocument(oldcore).getExactAttachment(filename);
        assertNotNull(attachment, "Expected attachment [" + filename + "] to be stored");
        return attachment.getContentInputStream(oldcore.getXWikiContext()).readAllBytes();
    }

    @Test
    void doorDenialSurfacesMessageWithoutSaving(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT)))
            .thenThrow(new MCPAccessDeniedException("Not authorized to edit \"" + REF + "\"."));

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, TEXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Not authorized"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void createsDocumentCarryingTheAttachmentWithoutBaseVersion(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, TEXT));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadTargetDocument(oldcore);
        assertFalse(saved.isNew());
        assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8), storedBytes(oldcore, NOTES_TXT));

        String text = textOf(result);
        assertTrue(text.contains("Created document " + CANONICAL + " with attachment \"notes.txt\" ("), text);
        assertTrue(text.contains(" as attachment version 1.1."), text);
        assertTrue(text.contains("Version: " + saved.getVersion()
            + " (base_version for next change: " + saved.getVersion() + ")"), text);
        assertTrue(text.contains("View: " + VIEW_URL), text);
    }

    @Test
    void attachingToExistingDocumentRequiresBaseVersion(MockitoOldcore oldcore) throws Exception
    {
        storeTargetDocument(oldcore);
        String versionBefore = targetVersion(oldcore);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, TEXT));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("already exists"), text);
        assertTrue(text.contains(BASE_VERSION_KEY), text);
        assertEquals(versionBefore, targetVersion(oldcore));
        assertNull(loadTargetDocument(oldcore).getExactAttachment(NOTES_TXT));
    }

    @Test
    void attachesNewAttachmentToExistingDocumentWithBaseVersion(MockitoOldcore oldcore) throws Exception
    {
        storeTargetDocument(oldcore);
        String versionBefore = targetVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            CONTENT_KEY, TEXT, BASE_VERSION_KEY, versionBefore));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadTargetDocument(oldcore);
        assertNotEquals(versionBefore, saved.getVersion());
        assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8), storedBytes(oldcore, NOTES_TXT));

        String text = textOf(result);
        assertTrue(text.contains("Attached \"notes.txt\" ("), text);
        assertTrue(text.contains(" as attachment version 1.1 on document " + CANONICAL + "."), text);
        assertTrue(text.contains("Version: " + versionBefore + " -> " + saved.getVersion()
            + " (base_version for next change: " + saved.getVersion() + ")"), text);
        assertTrue(text.contains("Compare: " + VIEW_URL), text);
    }

    @Test
    void baseVersionOnCreateRefused(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            CONTENT_KEY, TEXT, BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("does not exist"), text);
        assertTrue(text.contains("omit base_version"), text);
        assertTrue(loadTargetDocument(oldcore).isNew());
    }

    @Test
    void staleBaseVersionRefusedWithConflictMessage(MockitoOldcore oldcore) throws Exception
    {
        storeTargetDocument(oldcore);
        String versionBefore = targetVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            CONTENT_KEY, TEXT, BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Version conflict"), text);
        assertTrue(text.contains(versionBefore), text);
        assertEquals(versionBefore, targetVersion(oldcore));
        assertNull(loadTargetDocument(oldcore).getExactAttachment(NOTES_TXT));
    }

    @Test
    void overwritingKeepsTheNameAndSaysPreviousRevisionsAreKept(MockitoOldcore oldcore) throws Exception
    {
        stubUrlFactory(oldcore);
        storeTargetDocument(oldcore);
        String firstVersion = targetVersion(oldcore);
        call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, TEXT,
            BASE_VERSION_KEY, firstVersion));
        String versionBefore = targetVersion(oldcore);
        // Simulate the real store's archive bump (XWikiHibernateStore's updateContentArchive effect):
        // the attachment version increments on the INSTANCE HANDED TO THE SAVE - the api wrapper's
        // internal clone - never on the tool's staged copy. The bump is injected through
        // checkSavingDocument, which the api wrapper calls with that exact instance right before the
        // save (re-stubbing saveDocument itself would replace the oldcore fixture's storage
        // emulation). This is what forces the echo to read post-save state; reading the staged
        // instance would echo the pre-bump 1.1.
        doAnswer(invocation -> {
            XWikiAttachment beingSaved =
                invocation.<XWikiDocument>getArgument(1).getExactAttachment(NOTES_TXT);
            if (beingSaved != null) {
                beingSaved.incrementVersion();
            }
            return null;
        }).when(oldcore.getSpyXWiki()).checkSavingDocument(any(DocumentReference.class),
            any(XWikiDocument.class), anyString(), anyBoolean(), any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            CONTENT_KEY, "replaced text", BASE_VERSION_KEY, versionBefore));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadTargetDocument(oldcore);
        assertArrayEquals("replaced text".getBytes(StandardCharsets.UTF_8), storedBytes(oldcore, NOTES_TXT));
        assertNotEquals(versionBefore, saved.getVersion());

        String text = textOf(result);
        // The bumped version proves the echo reads the SAVED instance, not the staged one.
        assertTrue(text.contains("Updated attachment \"notes.txt\" (13 bytes, text/plain) to version 1.2"),
            text);
        assertTrue(text.contains(" on document " + CANONICAL + " (previous revisions are kept in its "
            + "history)."), text);
        assertTrue(text.contains("Version: " + versionBefore + " -> " + saved.getVersion()), text);
        assertTrue(text.contains("Download: " + DOWNLOAD_URL), text);
    }

    @Test
    void neitherContentFormRefusedWithoutResolving(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Error: provide exactly one of 'content' (text) or 'content_base64' (small binary "
            + "file). Nothing was saved.", textOf(result));
        verify(this.documentAccess, never()).resolveAndAuthorize(anyString(), any());
        verifyNothingSaved(oldcore);
    }

    @Test
    void bothContentFormsRefused(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            CONTENT_KEY, TEXT, CONTENT_BASE64_KEY, PIXEL_PNG_BASE64));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("exactly one of"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void invalidBase64RefusedWithoutSaving(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            CONTENT_BASE64_KEY, "not-valid-base64!!!"));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Error: 'content_base64' is not valid base64. Nothing was saved.", textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void overCapContentRefusedAsSmallFileDoor(MockitoOldcore oldcore) throws Exception
    {
        String huge = "x".repeat(MCPWriteSupport.MAX_CONTENT_CHARS + 1);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, huge));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.startsWith("Error: 'content' is longer than "), text);
        assertTrue(text.contains("small-file door"), text);
        assertTrue(text.contains("wiki UI"), text);
        verifyNothingSaved(oldcore);
    }

    @Test
    void overCapBase64RefusedAsSmallFileDoor(MockitoOldcore oldcore) throws Exception
    {
        String huge = "A".repeat(MCPWriteSupport.MAX_CONTENT_CHARS + 4);

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_BASE64_KEY, huge));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).startsWith("Error: 'content_base64' is longer than "), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void separatorFilenamesRefused(MockitoOldcore oldcore) throws Exception
    {
        for (String bad : List.of("a/b.txt", "a\\b.txt", "a;b.txt")) {
            McpSchema.CallToolResult result =
                call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, bad, CONTENT_KEY, TEXT));

            assertEquals(Boolean.TRUE, result.isError(), bad);
            String text = textOf(result);
            assertTrue(text.contains("must not contain"), text);
            assertTrue(text.contains(bad), text);
            assertTrue(text.contains("Nothing was saved."), text);
        }
        verifyNothingSaved(oldcore);
    }

    @Test
    void controlCharacterFilenameRefusedWithNeutralizedEcho(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "evil\nname.txt", CONTENT_KEY, TEXT));

        assertEquals(Boolean.TRUE, result.isError());
        String refusal = textOf(result);
        assertTrue(refusal.contains("must not contain"), refusal);
        assertTrue(refusal.contains("control characters"), refusal);
        // The smuggled newline cannot forge an extra line of the refusal's own grammar.
        assertTrue(refusal.contains("\"evilname.txt\""), refusal);
        assertFalse(refusal.contains("evil\nname"), refusal);
        verifyNothingSaved(oldcore);
    }

    @Test
    void bidiFormattingFilenameRefused(MockitoOldcore oldcore) throws Exception
    {
        // A right-to-left override would make the stored bytes "report<RLO>fdp.exe" DISPLAY as a name
        // ending .txt-like while actually ending .exe - refuse instead of storing the spoofable name.
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF,
            FILENAME_KEY, "report\u202Efdp.exe", CONTENT_KEY, TEXT));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("directional formatting characters"), text);
        // The refusal echo itself is neutralized: the override never reaches the wire.
        assertFalse(text.contains("\u202E"), "The override must be stripped from the echo");
        verifyNothingSaved(oldcore);
    }

    @Test
    void overlongFilenameIsClampedInSuccessEcho(MockitoOldcore oldcore) throws Exception
    {
        // A very long (but legal) filename is cut with an ellipsis in the success echo.
        String longName = "x".repeat(250) + ".txt";

        String success = callText(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, longName, CONTENT_KEY, TEXT));

        assertTrue(success.contains("\"" + "x".repeat(200) + "…\""), success);
        assertFalse(success.contains(longName), success);
    }

    @Test
    void contentWithTrailingNewlineRoundTripsByteExact(MockitoOldcore oldcore) throws Exception
    {
        String verbatim = "line one\r\nline two\n\n";

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, verbatim));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The bytes are stored VERBATIM: no trimming and no line-ending normalization - file content.
        assertArrayEquals(verbatim.getBytes(StandardCharsets.UTF_8), storedBytes(oldcore, NOTES_TXT));
    }

    @Test
    void emptyContentCreatesZeroByteAttachment(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, ""));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertEquals(0, storedBytes(oldcore, NOTES_TXT).length);
        assertTrue(textOf(result).contains("(0 bytes"), textOf(result));
    }

    @Test
    void whitespaceOnlyContentIsPreservedVerbatim(MockitoOldcore oldcore) throws Exception
    {
        String whitespace = "  \n ";

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, whitespace));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertArrayEquals(whitespace.getBytes(StandardCharsets.UTF_8), storedBytes(oldcore, NOTES_TXT));
    }

    @Test
    void emptyBase64RefusedPointingAtEmptyContent(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_BASE64_KEY, " "));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Error: 'content_base64' is empty. To create an empty file, pass content=\"\" "
            + "instead. Nothing was saved.", textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void missingValidatorFailsClosed(MockitoOldcore oldcore) throws Exception
    {
        storeTargetDocument(oldcore);
        String versionBefore = targetVersion(oldcore);
        // The default validator ships as a flavor-installed extension: on a wiki without it, the
        // provider lookup fails and uploads are refused rather than saved unvalidated.
        when(this.attachmentValidatorProvider.get()).thenThrow(new RuntimeException("no such component"));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            CONTENT_KEY, TEXT, BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Attachment validation is not available on this wiki"), text);
        assertTrue(text.contains("xwiki-platform-attachment-validation-default"), text);
        assertTrue(text.contains("Nothing was saved."), text);
        assertEquals(versionBefore, targetVersion(oldcore));
        assertNull(loadTargetDocument(oldcore).getExactAttachment(NOTES_TXT));
    }

    @Test
    void binaryOverwriteClearsTheStaleTextCharset(MockitoOldcore oldcore) throws Exception
    {
        String filename = "data.bin";
        call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, filename, CONTENT_KEY, TEXT));
        assertEquals("UTF-8",
            loadTargetDocument(oldcore).getExactAttachment(filename).getCharset());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, filename,
            CONTENT_BASE64_KEY, PIXEL_PNG_BASE64, BASE_VERSION_KEY, targetVersion(oldcore)));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The binary content must not keep the previous text content's charset.
        assertNull(loadTargetDocument(oldcore).getExactAttachment(filename).getCharset());
    }

    @Test
    void sensitiveDocumentRefusedRegardlessOfVersion(MockitoOldcore oldcore) throws Exception
    {
        DocumentReference webPreferences = new DocumentReference("xwiki", "Sandbox", "WebPreferences");
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(webPreferences);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, "Sandbox.WebPreferences",
            FILENAME_KEY, NOTES_TXT, CONTENT_KEY, TEXT));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Refusing to attach to"), text);
        assertTrue(text.contains("access rights or wiki configuration"), text);
        verifyNothingSaved(oldcore);
    }

    @Test
    void policyRefusalCarriesTheValidatorMessageAndSavesNothing(MockitoOldcore oldcore) throws Exception
    {
        storeTargetDocument(oldcore);
        String versionBefore = targetVersion(oldcore);
        doThrow(new AttachmentValidationException("File too big", 413, "attachment.validation.size",
            "Maximum file size: 10 bytes")).when(this.attachmentValidator).validateAttachment(any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            CONTENT_KEY, TEXT, BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Attachment refused by this wiki's attachment policy: File too big "
            + "Maximum file size: 10 bytes"), text);
        assertTrue(text.contains("Nothing was saved."), text);
        assertEquals(versionBefore, targetVersion(oldcore));
        assertNull(loadTargetDocument(oldcore).getExactAttachment(NOTES_TXT));
    }

    @Test
    void validatorReceivesAWrapperExposingTheStagedFile(MockitoOldcore oldcore) throws Exception
    {
        storeTargetDocument(oldcore);

        call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, TEXT,
            BASE_VERSION_KEY, targetVersion(oldcore)));

        ArgumentCaptor<AttachmentAccessWrapper> wrapper =
            ArgumentCaptor.forClass(AttachmentAccessWrapper.class);
        verify(this.attachmentValidator).validateAttachment(wrapper.capture());
        assertEquals(NOTES_TXT, wrapper.getValue().getFileName());
        assertEquals(TEXT.getBytes(StandardCharsets.UTF_8).length, wrapper.getValue().getSize());
    }

    @Test
    void textPathStoresMimetypeAndUtf8Charset(MockitoOldcore oldcore) throws Exception
    {
        storeTargetDocument(oldcore);

        call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, TEXT,
            BASE_VERSION_KEY, targetVersion(oldcore)));

        XWikiAttachment attachment = loadTargetDocument(oldcore).getExactAttachment(NOTES_TXT);
        assertNotNull(attachment);
        // resetMimeType ran: the stored mimetype is detected, not left empty.
        assertEquals("text/plain", attachment.getMimeType());
        assertEquals("UTF-8", attachment.getCharset());
    }

    @Test
    void base64BytesRoundTripIntoTheStoredAttachment(MockitoOldcore oldcore) throws Exception
    {
        byte[] png = Base64.getDecoder().decode(PIXEL_PNG_BASE64);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "pixel.png",
            CONTENT_BASE64_KEY, PIXEL_PNG_BASE64));

        assertNotEquals(Boolean.TRUE, result.isError());
        assertArrayEquals(png, storedBytes(oldcore, "pixel.png"));
        XWikiAttachment attachment = loadTargetDocument(oldcore).getExactAttachment("pixel.png");
        assertEquals("image/png", attachment.getMimeType());
        // The binary path records no text charset.
        assertNull(attachment.getCharset());
    }

    @Test
    void agentCommentIsPrefixedSetOnAttachmentAndUpdateIsMinor(MockitoOldcore oldcore) throws Exception
    {
        storeTargetDocument(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            CONTENT_KEY, TEXT, BASE_VERSION_KEY, targetVersion(oldcore), COMMENT_KEY, "add release notes"));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The [AI] prefix is always prepended, and an update is recorded as a minor edit.
        verify(oldcore.getSpyXWiki()).saveDocument(any(XWikiDocument.class),
            eq("[AI] add release notes"), eq(true), any());
        XWikiDocument saved = loadTargetDocument(oldcore);
        assertEquals("[AI] add release notes", saved.getComment());
        assertEquals("[AI] add release notes", saved.getExactAttachment(NOTES_TXT).getComment());
    }

    @Test
    void overlongCommentIsAbbreviated(MockitoOldcore oldcore) throws Exception
    {
        storeTargetDocument(oldcore);

        call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, TEXT,
            BASE_VERSION_KEY, targetVersion(oldcore), COMMENT_KEY, "y".repeat(2000)));

        String storedComment = loadTargetDocument(oldcore).getComment();
        assertTrue(storedComment.startsWith("[AI] "), storedComment);
        assertTrue(storedComment.length() <= 1000, "Comment length " + storedComment.length());
    }

    @Test
    void createIsRecordedAsANonMinorSave(MockitoOldcore oldcore) throws Exception
    {
        call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, TEXT));

        // Creation is a normal (major) save, mirroring the other write tools.
        verify(oldcore.getSpyXWiki()).saveDocument(any(XWikiDocument.class),
            eq("[AI] Created document"), eq(false), any());
    }

    @Test
    void createRaceReroutesToTheReadFirstMessage(MockitoOldcore oldcore) throws Exception
    {
        // The winner's row is committed by the time the loser handles its failure, so the fresh
        // re-check sees the document existing and the loser gets the same read-first guidance the
        // pre-save exists-guard produces.
        loseRaceOnSave(oldcore, createRaceException());

        McpSchema.CallToolResult result =
            call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT, CONTENT_KEY, TEXT));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Document \"Sandbox.WebHome\" already exists. First read it with get_document and "
            + "pass the base_version it shows, so the attachment change is based on a recent read.",
            textOf(result));
        assertTrue(this.logCapture.getMessage(0).contains("lost a create race"), this.logCapture.getMessage(0));
    }

    @Test
    void concurrentCollisionOnUpdateReturnsTheRetryMessage(MockitoOldcore oldcore) throws Exception
    {
        storeTargetDocument(oldcore);
        String versionBefore = targetVersion(oldcore);
        failSave(oldcore, serializationCollisionException());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            CONTENT_KEY, TEXT, BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Could not save the document: the save collided with another write happening at the "
            + "same time. Retry the call; sending writes one at a time avoids this.", textOf(result));
        assertTrue(this.logCapture.getMessage(0).contains("collided with a concurrent write"),
            this.logCapture.getMessage(0));
    }

    @Test
    void storageFailureReturnsFixedMessageAndLogsRootCause(MockitoOldcore oldcore) throws Exception
    {
        storeTargetDocument(oldcore);
        String versionBefore = targetVersion(oldcore);
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_UNKNOWN,
            "db down")).when(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            CONTENT_KEY, TEXT, BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        // The fixed message reaches the wire; the storage internals stay in the server logs.
        assertTrue(text.contains("Could not save the document"), text);
        assertFalse(text.contains("db down"), text);
        assertTrue(this.logCapture.getMessage(0).contains("db down"), this.logCapture.getMessage(0));
        // The mutation happened on the tool's clone, so the stored document still has no attachment.
        assertNull(loadTargetDocument(oldcore).getExactAttachment(NOTES_TXT));
    }

    @Test
    void crossWikiSaveRunsInTargetWikiAndRestoresContextWiki(MockitoOldcore oldcore) throws Exception
    {
        DocumentReference otherRef = new DocumentReference("otherwiki", "Sandbox", "WebHome");
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(otherRef);

        String originalWiki = oldcore.getXWikiContext().getWikiId();
        AtomicReference<String> wikiAtSave = new AtomicReference<>();
        // Record the context wiki at save time without persisting: oldcore only registers components for the
        // main wiki, so a real save under the switched "otherwiki" namespace cannot resolve its serializers.
        doAnswer(invocation -> {
            wikiAtSave.set(oldcore.getXWikiContext().getWikiId());
            return null;
        }).when(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, "otherwiki:Sandbox.WebHome",
            FILENAME_KEY, NOTES_TXT, CONTENT_KEY, TEXT));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The save ran with the context wiki switched to the target wiki, so save-time rights and
        // attachment policy resolution apply in that wiki.
        assertEquals("otherwiki", wikiAtSave.get());
        // The original context wiki is restored once the save completes.
        assertEquals(originalWiki, oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void schemaDeclaresParamsInImportanceOrderWithReferenceAndFilenameRequired()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();
        Map<?, ?> properties = (Map<?, ?>) definition.inputSchema().get("properties");
        List<Object> keys = new ArrayList<>(properties.keySet());
        assertEquals(List.of(REFERENCE_KEY, FILENAME_KEY, CONTENT_KEY, CONTENT_BASE64_KEY,
            BASE_VERSION_KEY, COMMENT_KEY), keys);
        assertEquals(List.of(REFERENCE_KEY, FILENAME_KEY), definition.inputSchema().get("required"));
    }

    @Test
    void isWriteAndCatalogMetadataAreSet()
    {
        assertTrue(this.tool.isWrite());
        assertEquals("Authoring", this.tool.getCategory());
        assertEquals("Attach a file to a document, or overwrite an existing attachment.",
            this.tool.getSummary());
        assertTrue(this.tool.getManPage().contains("EXAMPLES"), this.tool.getManPage());
        assertTrue(this.tool.getManPage().contains("base_version"), this.tool.getManPage());
        assertTrue(this.tool.getManPage().contains("small-file door"), this.tool.getManPage());
    }

    @Test
    void reachOnMentionsCrossWikiInReferenceDescription()
    {
        when(this.wikiReach.isReachEnabled()).thenReturn(true);

        String description = referenceDescription();

        assertTrue(description.contains("wiki-id prefix"), description);
        assertTrue(description.contains("xwiki:"), description);
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
