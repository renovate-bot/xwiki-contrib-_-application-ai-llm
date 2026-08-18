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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Named;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import io.modelcontextprotocol.spec.McpSchema;

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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.mcp.internal.tool.MCPToolTestUtils.textOf;

/**
 * Tests for {@link MCPDeleteAttachmentTool}, against the real oldcore store and real attachments so the
 * removal semantics are exercised end to end: the attachment leaves the list, the others survive, and
 * the saved instance carries the queued removal (with its recycle-bin flag) that a real store's
 * deletion branch consumes.
 *
 * @version $Id$
 */
@OldcoreTest
class MCPDeleteAttachmentToolTest extends AbstractMCPWriteToolTest
{
    private static final String REFERENCE_KEY = "reference";

    private static final String FILENAME_KEY = "filename";

    private static final String BASE_VERSION_KEY = "base_version";

    private static final String COMMENT_KEY = "comment";

    private static final String REF = "Sandbox.WebHome";

    private static final String CANONICAL = "xwiki:Sandbox.WebHome";

    private static final String NOTES_TXT = "notes.txt";

    private static final String LOGO_PNG = "logo.png";

    private static final String TEXT = "line one\nline two";

    private static final String VIEW_URL = "https://wiki.example/bin/view/Sandbox/WebHome";

    private static final DocumentReference DOC_REFERENCE = new DocumentReference("xwiki", "Sandbox", "WebHome");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPDeleteAttachmentTool tool;

    @MockComponent
    @Named("local")
    private EntityReferenceSerializer<String> localSerializer;

    @BeforeEach
    void setUp(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(DOC_REFERENCE);
        lenient().when(this.serializer.serialize(any())).thenReturn(CANONICAL);
        lenient().when(this.documentAccessBridge.getDocumentURL(any(), eq("view"), any(), any(), eq(true)))
            .thenReturn(VIEW_URL);
        // The attachment recycle bin is enabled by default; the permanence-refusal test overrides this.
        lenient().doReturn(true).when(oldcore.getSpyXWiki()).hasAttachmentRecycleBin(any());

        allowSimpleSavePath(oldcore);
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    /**
     * Stores the target document carrying two real attachments ({@code notes.txt} and {@code logo.png}).
     */
    private void storeDocumentWithAttachments(MockitoOldcore oldcore) throws Exception
    {
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        doc.setContent("body");
        XWikiAttachment notes = new XWikiAttachment(doc, NOTES_TXT);
        notes.setContent(new ByteArrayInputStream(TEXT.getBytes(StandardCharsets.UTF_8)));
        notes.setMimeType("text/plain");
        doc.setAttachment(notes);
        XWikiAttachment logo = new XWikiAttachment(doc, LOGO_PNG);
        logo.setContent(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        logo.setMimeType("image/png");
        doc.setAttachment(logo);
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());
    }

    private XWikiDocument loadTargetDocument(MockitoOldcore oldcore) throws Exception
    {
        return loadDocument(oldcore, DOC_REFERENCE);
    }

    private String targetVersion(MockitoOldcore oldcore) throws Exception
    {
        return currentVersion(oldcore, DOC_REFERENCE);
    }

    @Test
    void doorDenialSurfacesMessageWithoutSaving(MockitoOldcore oldcore) throws Exception
    {
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT)))
            .thenThrow(new MCPAccessDeniedException("Not authorized to edit \"" + REF + "\"."));

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Not authorized"), textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void missingDocumentRefused(MockitoOldcore oldcore) throws Exception
    {
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            BASE_VERSION_KEY, "1.1"));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Document \"" + REF + "\" does not exist; there is no attachment to delete.",
            textOf(result));
        verifyNothingSaved(oldcore);
    }

    @Test
    void missingAttachmentRefusalListsExistingNames(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithAttachments(oldcore);
        String versionBefore = targetVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "missing.txt",
            BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Attachment \"missing.txt\" not found on \"" + REF + "\"."), text);
        // The platform's attachment list is filename-sorted, so the refusal lists names alphabetically.
        assertTrue(text.contains("Attachments on this document: logo.png, notes.txt."), text);
        assertEquals(versionBefore, targetVersion(oldcore));
    }

    @Test
    void missingAttachmentOnBareDocumentSaysNoAttachments(MockitoOldcore oldcore) throws Exception
    {
        storeDocument(oldcore, DOC_REFERENCE, "body", null);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            BASE_VERSION_KEY, targetVersion(oldcore)));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("This document has no attachments."), textOf(result));
    }

    @Test
    void hostileFilenameIsNeutralizedInTheMissRefusal(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithAttachments(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "evil\nname.txt",
            BASE_VERSION_KEY, targetVersion(oldcore)));

        // The smuggled newline cannot forge an extra line of the refusal's grammar.
        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).contains("Attachment \"evilname.txt\" not found"), textOf(result));
        assertFalse(textOf(result).contains("evil\nname"), textOf(result));
    }

    @Test
    void filenameMatchingIsExactNotFuzzy(MockitoOldcore oldcore) throws Exception
    {
        // The platform's fuzzy getAttachment would serve "report.pdf" for "report"; the tool must miss
        // instead and teach the exact name.
        XWikiDocument doc = new XWikiDocument(DOC_REFERENCE);
        XWikiAttachment report = new XWikiAttachment(doc, "report.pdf");
        report.setContent(new ByteArrayInputStream(new byte[] {1}));
        doc.setAttachment(report);
        oldcore.getSpyXWiki().saveDocument(doc, oldcore.getXWikiContext());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, "report",
            BASE_VERSION_KEY, targetVersion(oldcore)));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Attachment \"report\" not found"), text);
        assertTrue(text.contains("Attachments on this document: report.pdf."), text);
        assertNotNull(loadTargetDocument(oldcore).getExactAttachment("report.pdf"));
    }

    @Test
    void staleBaseVersionRefusedWithConflictMessage(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithAttachments(oldcore);
        String versionBefore = targetVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Version conflict"), text);
        assertTrue(text.contains(versionBefore), text);
        assertTrue(text.contains("retry the removal if you still intend it."), text);
        assertEquals(versionBefore, targetVersion(oldcore));
        assertNotNull(loadTargetDocument(oldcore).getExactAttachment(NOTES_TXT));
    }

    @Test
    void noAttachmentRecycleBinRefusedBeforeAnyMutation(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithAttachments(oldcore);
        String versionBefore = targetVersion(oldcore);
        doReturn(false).when(oldcore.getSpyXWiki()).hasAttachmentRecycleBin(any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("This wiki has no attachment recycle bin, so deletion would be permanent. Refusing; "
            + "delete via the wiki UI if you really intend this.", textOf(result));
        assertEquals(versionBefore, targetVersion(oldcore));
        assertNotNull(loadTargetDocument(oldcore).getExactAttachment(NOTES_TXT));
    }

    @Test
    void deletesAttachmentAndSavesNewVersionWithEchoes(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithAttachments(oldcore);
        String versionBefore = targetVersion(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            BASE_VERSION_KEY, versionBefore));

        assertNotEquals(Boolean.TRUE, result.isError());
        XWikiDocument saved = loadTargetDocument(oldcore);
        assertNull(saved.getExactAttachment(NOTES_TXT), "The deleted attachment must be gone");
        assertNotNull(saved.getExactAttachment(LOGO_PNG), "The other attachment must survive");
        assertNotEquals(versionBefore, saved.getVersion());
        assertEquals("[AI] deleted attachment notes.txt", saved.getComment());
        // The instance handed to the store carries the QUEUED removal with its recycle-bin flag: this
        // queue - not the shrunk list - is what a real store's deletion branch consumes (recycle-bin
        // copy, deletion event, row deletion), and XWikiDocument.clone() does not carry it, so staging
        // on the wrong instance would make the delete a silent no-op against a real store.
        ArgumentCaptor<XWikiDocument> savedInstance = ArgumentCaptor.forClass(XWikiDocument.class);
        // Two saves hit the spy: the fixture's store of the document, then the tool's removal save;
        // getValue() reads the LAST captured instance - the one the tool handed to the store.
        verify(oldcore.getSpyXWiki(), times(2))
            .saveDocument(savedInstance.capture(), anyString(), anyBoolean(), any());
        List<XWikiDocument.XWikiAttachmentToRemove> toRemove = savedInstance.getValue().getAttachmentsToRemove();
        assertEquals(1, toRemove.size(), "Exactly one removal must be queued for the store");
        assertEquals(NOTES_TXT, toRemove.get(0).getAttachment().getFilename());
        assertTrue(toRemove.get(0).isToRecycleBin(), "The removal must be routed to the recycle bin");

        String text = textOf(result);
        assertTrue(text.contains("Deleted attachment \"notes.txt\" (17 bytes, text/plain) from "
            + CANONICAL + ". It can be restored from the attachment recycle bin via the wiki UI."), text);
        assertTrue(text.contains("Version: " + versionBefore + " -> " + saved.getVersion()
            + " (base_version for next change: " + saved.getVersion() + ")"), text);
        assertTrue(text.contains("Compare: " + VIEW_URL), text);
    }

    @Test
    void removalIsRecordedAsAMinorSaveWithTheAgentComment(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithAttachments(oldcore);

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            BASE_VERSION_KEY, targetVersion(oldcore), COMMENT_KEY, "drop draft notes"));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The [AI] prefix is always prepended, and a removal is recorded as a minor edit like
        // delete_object's.
        verify(oldcore.getSpyXWiki()).saveDocument(any(XWikiDocument.class),
            eq("[AI] drop draft notes"), eq(true), any());
    }

    @Test
    void sensitiveDocumentRefusedRegardlessOfVersion(MockitoOldcore oldcore) throws Exception
    {
        DocumentReference webPreferences = new DocumentReference("xwiki", "Sandbox", "WebPreferences");
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(webPreferences);
        storeDocument(oldcore, webPreferences, "prefs", null);
        String versionBefore = currentVersion(oldcore, webPreferences);

        // A deliberately stale base_version: the sensitive refusal must fire BEFORE the version check,
        // so a denylisted target is refused as such rather than draped as a version conflict.
        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, "Sandbox.WebPreferences",
            FILENAME_KEY, NOTES_TXT, BASE_VERSION_KEY, "9.9"));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Refusing to delete attachments on"), text);
        assertTrue(text.contains("access rights or wiki configuration"), text);
        assertFalse(text.contains("Version conflict"), text);
        assertEquals(versionBefore, currentVersion(oldcore, webPreferences));
    }

    @Test
    void concurrentCollisionReturnsTheRetryMessage(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithAttachments(oldcore);
        String versionBefore = targetVersion(oldcore);
        failSave(oldcore, serializationCollisionException());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("Could not save the document: the save collided with another write happening at the "
            + "same time. Retry the call; sending writes one at a time avoids this.", textOf(result));
        assertTrue(this.logCapture.getMessage(0).contains("collided with a concurrent write"),
            this.logCapture.getMessage(0));
        // The removal happened on the tool's clone, so the stored document still has the attachment.
        assertNotNull(loadTargetDocument(oldcore).getExactAttachment(NOTES_TXT));
    }

    @Test
    void storageFailureReturnsFixedMessageAndLogsRootCause(MockitoOldcore oldcore) throws Exception
    {
        storeDocumentWithAttachments(oldcore);
        String versionBefore = targetVersion(oldcore);
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI_STORE, XWikiException.ERROR_XWIKI_UNKNOWN,
            "db down")).when(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, REF, FILENAME_KEY, NOTES_TXT,
            BASE_VERSION_KEY, versionBefore));

        assertEquals(Boolean.TRUE, result.isError());
        String text = textOf(result);
        assertTrue(text.contains("Could not save the document"), text);
        assertFalse(text.contains("db down"), text);
        assertTrue(this.logCapture.getMessage(0).contains("db down"), this.logCapture.getMessage(0));
        assertNotNull(loadTargetDocument(oldcore).getExactAttachment(NOTES_TXT));
    }

    @Test
    void crossWikiSaveRunsInTargetWikiAndRestoresContextWiki(MockitoOldcore oldcore) throws Exception
    {
        DocumentReference otherRef = new DocumentReference("otherwiki", "Sandbox", "WebHome");
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.EDIT))).thenReturn(otherRef);
        // Store the target row under the other wiki's reference so the load inside the switched context
        // finds it.
        XWikiDocument doc = new XWikiDocument(otherRef);
        doc.setContent("body");
        XWikiAttachment notes = new XWikiAttachment(doc, NOTES_TXT);
        notes.setContent(new ByteArrayInputStream(new byte[] {1}));
        doc.setAttachment(notes);
        // Straight into the store map (the fixture save helpers only serve the main wiki), marked as
        // existing the way the fixture's own save answer marks saved rows.
        doc.setNew(false);
        oldcore.getDocuments().put(doc.getDocumentReferenceWithLocale(), doc);

        String originalWiki = oldcore.getXWikiContext().getWikiId();
        AtomicReference<String> wikiAtSave = new AtomicReference<>();
        // Record the context wiki at save time without persisting: oldcore only registers components for
        // the main wiki, so a real save under the switched "otherwiki" namespace cannot resolve its
        // serializers.
        doAnswer(invocation -> {
            wikiAtSave.set(oldcore.getXWikiContext().getWikiId());
            return null;
        }).when(oldcore.getSpyXWiki())
            .saveDocument(any(XWikiDocument.class), anyString(), anyBoolean(), any());

        McpSchema.CallToolResult result = call(Map.of(REFERENCE_KEY, "otherwiki:Sandbox.WebHome",
            FILENAME_KEY, NOTES_TXT, BASE_VERSION_KEY, doc.getVersion()));

        assertNotEquals(Boolean.TRUE, result.isError());
        // The save ran with the context wiki switched to the target wiki, so save-time rights apply
        // there; the original context wiki is restored once the save completes.
        assertEquals("otherwiki", wikiAtSave.get());
        assertEquals(originalWiki, oldcore.getXWikiContext().getWikiId());
    }

    @Test
    void schemaDeclaresParamsInImportanceOrderWithBaseVersionRequired()
    {
        McpSchema.Tool definition = this.tool.getToolDefinition();
        Map<?, ?> properties = (Map<?, ?>) definition.inputSchema().get("properties");
        List<Object> keys = new ArrayList<>(properties.keySet());
        assertEquals(List.of(REFERENCE_KEY, FILENAME_KEY, BASE_VERSION_KEY, COMMENT_KEY), keys);
        assertEquals(List.of(REFERENCE_KEY, FILENAME_KEY, BASE_VERSION_KEY),
            definition.inputSchema().get("required"));
    }

    @Test
    void isWriteAndCatalogMetadataAreSet()
    {
        assertTrue(this.tool.isWrite());
        assertEquals("Authoring", this.tool.getCategory());
        assertEquals("Delete an attachment from a document.", this.tool.getSummary());
        assertTrue(this.tool.getManPage().contains("EXAMPLES"), this.tool.getManPage());
        assertTrue(this.tool.getManPage().contains("recycle bin"), this.tool.getManPage());
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
