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

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.Logger;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.mcp.internal.tool.MCPToolTestUtils.textOf;

/**
 * Tests for {@link MCPWriteSupport}: the version-comment construction (the {@code [AI] } prefix is a
 * stable contract, pinned literally here), the minor-edit policy, the wiki-switch scaffold's
 * failure-path restore, and the save-failure recovery predicates. The scaffold's success path, the
 * shared message fragments and the recovery's end-to-end wiring are covered through the write tools'
 * tests.
 *
 * @version $Id$
 */
class MCPWriteSupportTest
{
    private static final DocumentReference DOC_REFERENCE =
        new DocumentReference("xwiki", "Sandbox", "WebHome");

    private static final String SAVE_FAILED = "save failed";

    /**
     * An update description whose appearance in the built comment would mean the agent comment or the
     * creation default was wrongly ignored.
     */
    private static final String UNUSED_DESCRIPTION = "unused description";

    @Test
    void inTargetWikiRestoresTheContextWikiWhenTheDocumentLoadThrows() throws Exception
    {
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        DocumentReference ref = new DocumentReference("second", "Sandbox", "WebHome");
        when(xcontext.getUserReference()).thenReturn(new DocumentReference("xwiki", "XWiki", "User"));
        when(xcontext.getWikiId()).thenReturn("xwiki");
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(ref, xcontext)).thenThrow(new XWikiException(0, 0, "Store down"));

        assertThrows(XWikiException.class, () -> MCPWriteSupport.inTargetWiki(xcontext, ref,
            (ctx, doc) -> MCPToolSupport.result("unreachable")));

        // The finally-restore must hold on the failure path too: a future refactor moving the load out of
        // the try block would otherwise leave the request thread pinned to the target wiki.
        InOrder order = inOrder(xcontext);
        order.verify(xcontext).setWikiId("second");
        order.verify(xcontext).setWikiId("xwiki");
    }

    @Test
    void buildCommentPrefixesAgentComment()
    {
        assertEquals("[AI] fix typo", MCPWriteSupport.buildComment("fix typo", false, UNUSED_DESCRIPTION));
    }

    @Test
    void buildCommentAgentCommentWinsOverCreationDefault()
    {
        assertEquals("[AI] initial import", MCPWriteSupport.buildComment("initial import", true,
            UNUSED_DESCRIPTION));
    }

    @Test
    void buildCommentBlankAgentCommentFallsBackToUpdateDescription()
    {
        assertEquals("[AI] 2 edits", MCPWriteSupport.buildComment("   ", false, "2 edits"));
    }

    @Test
    void buildCommentNullAgentCommentOnCreationUsesCreatedDefault()
    {
        assertEquals("[AI] Created document", MCPWriteSupport.buildComment(null, true, UNUSED_DESCRIPTION));
    }

    @Test
    void buildCommentAbbreviatesAtOneThousandCharactersKeepingPrefix()
    {
        String comment = MCPWriteSupport.buildComment("c".repeat(2000), false, UNUSED_DESCRIPTION);

        assertEquals(1000, comment.length());
        assertTrue(comment.startsWith("[AI] cc"), comment);
        assertTrue(comment.endsWith("..."), comment);
    }

    @Test
    void isMinorEditOnlyForNonMajorSaveOfExistingDocument()
    {
        assertFalse(MCPWriteSupport.isMinorEdit(true, false));
        assertFalse(MCPWriteSupport.isMinorEdit(true, true));
        assertFalse(MCPWriteSupport.isMinorEdit(false, true));
        assertTrue(MCPWriteSupport.isMinorEdit(false, false));
    }

    @Test
    void isNoOpWriteOnlyForAnUnchangedExistingRow()
    {
        assertTrue(MCPWriteSupport.isNoOpWrite(false, true, false, false));
        assertFalse(MCPWriteSupport.isNoOpWrite(true, true, false, false));
        assertFalse(MCPWriteSupport.isNoOpWrite(false, false, false, false));
        assertFalse(MCPWriteSupport.isNoOpWrite(false, true, true, false));
        assertFalse(MCPWriteSupport.isNoOpWrite(false, true, false, true));
    }

    @Test
    void isStorageSaveErrorMatchesOnlyTheStoreModuleAndSaveCode()
    {
        assertTrue(MCPWriteSupport.isStorageSaveError(new XWikiException(XWikiException.MODULE_XWIKI_STORE,
            XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_DOC, SAVE_FAILED)));
        assertFalse(MCPWriteSupport.isStorageSaveError(new XWikiException(XWikiException.MODULE_XWIKI_STORE,
            XWikiException.ERROR_XWIKI_UNKNOWN, SAVE_FAILED)));
        assertFalse(MCPWriteSupport.isStorageSaveError(new XWikiException(0,
            XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_DOC, SAVE_FAILED)));
    }

    @Test
    void isConcurrencyCollisionFindsCollisionSqlStatesAtAnyDepth()
    {
        // The SQL exception sits two wrappers deep, mirroring a driver or batch wrapper interposing.
        Throwable constraintDeep = new XWikiException(XWikiException.MODULE_XWIKI_STORE,
            XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_DOC, SAVE_FAILED,
            new RuntimeException(new SQLIntegrityConstraintViolationException("duplicate", "23505", 0)));
        assertTrue(MCPWriteSupport.isConcurrencyCollision(constraintDeep));

        Throwable serialization = new RuntimeException(new SQLException("serialization failure", "40001"));
        assertTrue(MCPWriteSupport.isConcurrencyCollision(serialization));
    }

    @Test
    void isConcurrencyCollisionIgnoresOtherAndAbsentSqlStates()
    {
        assertFalse(MCPWriteSupport.isConcurrencyCollision(new RuntimeException("no sql at all")));
        assertFalse(MCPWriteSupport.isConcurrencyCollision(new SQLException("no state")));
        assertFalse(MCPWriteSupport.isConcurrencyCollision(new SQLException("other state", "S1000")));
    }

    @Test
    void existsFreshLoadsTheExactTranslationRow() throws Exception
    {
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        when(xcontext.getWiki()).thenReturn(xwiki);
        XWikiDocument row = mock(XWikiDocument.class);
        when(row.isNew()).thenReturn(false);
        when(xwiki.getDocument(new DocumentReference(DOC_REFERENCE, Locale.FRENCH), xcontext))
            .thenReturn(row);

        assertTrue(MCPWriteSupport.existsFresh(DOC_REFERENCE, Locale.FRENCH, xcontext, mock(Logger.class)));
    }

    @Test
    void existsFreshIsFalseWhenTheRowIsStillAbsent() throws Exception
    {
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        when(xcontext.getWiki()).thenReturn(xwiki);
        XWikiDocument absent = mock(XWikiDocument.class);
        when(absent.isNew()).thenReturn(true);
        when(xwiki.getDocument(DOC_REFERENCE, xcontext)).thenReturn(absent);

        assertFalse(MCPWriteSupport.existsFresh(DOC_REFERENCE, null, xcontext, mock(Logger.class)));
    }

    @Test
    void existsFreshIsBestEffortOnLoadFailureAndMissingContext() throws Exception
    {
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        when(xcontext.getWiki()).thenReturn(xwiki);
        when(xwiki.getDocument(DOC_REFERENCE, xcontext)).thenThrow(new XWikiException(0, 0, "Store down"));

        assertFalse(MCPWriteSupport.existsFresh(DOC_REFERENCE, null, xcontext, mock(Logger.class)));
        assertFalse(MCPWriteSupport.existsFresh(DOC_REFERENCE, null, null, mock(Logger.class)));
    }

    @Test
    void reroutedSaveFailureIsNullForNonStorageFailures()
    {
        assertNull(MCPWriteSupport.reroutedSaveFailure(new XWikiException(0, 0, "boom"),
            mock(XWikiContext.class), DOC_REFERENCE, null, true, "unused", mock(Logger.class)));
    }

    @Test
    void reroutedSaveFailureIsNullForAStorageFailureWithNoRecognizedShape()
    {
        // Not creating and no collision-class SQL state: the caller rethrows to its generic handler.
        XWikiException failure = new XWikiException(XWikiException.MODULE_XWIKI_STORE,
            XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_DOC, SAVE_FAILED,
            new RuntimeException("connection reset"));

        assertNull(MCPWriteSupport.reroutedSaveFailure(failure, mock(XWikiContext.class), DOC_REFERENCE,
            null, false, null, mock(Logger.class)));
    }

    @Test
    void reroutedSaveFailureLostCreateRaceWinsOverTheCollisionMessage() throws Exception
    {
        XWikiContext xcontext = mock(XWikiContext.class);
        XWiki xwiki = mock(XWiki.class);
        when(xcontext.getWiki()).thenReturn(xwiki);
        XWikiDocument committed = mock(XWikiDocument.class);
        when(committed.isNew()).thenReturn(false);
        when(xwiki.getDocument(DOC_REFERENCE, xcontext)).thenReturn(committed);
        // The constraint violation also matches the collision predicate; the create-race re-route is
        // tried first because its read-first guidance is the more specific recovery.
        XWikiException failure = new XWikiException(XWikiException.MODULE_XWIKI_STORE,
            XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_DOC, SAVE_FAILED,
            new RuntimeException(new SQLIntegrityConstraintViolationException("duplicate", "23505", 0)));

        McpSchema.CallToolResult result = MCPWriteSupport.reroutedSaveFailure(failure, xcontext,
            DOC_REFERENCE, null, true, "read it first", mock(Logger.class));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals("read it first", textOf(result));
    }

    @Test
    void reroutedSaveFailureCollisionReturnsTheSharedRetryMessage()
    {
        XWikiException failure = new XWikiException(XWikiException.MODULE_XWIKI_STORE,
            XWikiException.ERROR_XWIKI_STORE_HIBERNATE_SAVING_DOC, SAVE_FAILED,
            new RuntimeException(new SQLException("serialization failure", "40001")));

        McpSchema.CallToolResult result = MCPWriteSupport.reroutedSaveFailure(failure,
            mock(XWikiContext.class), DOC_REFERENCE, null, false, null, mock(Logger.class));

        assertEquals(Boolean.TRUE, result.isError());
        assertEquals(MCPWriteSupport.CONCURRENT_SAVE_MESSAGE, textOf(result));
    }
}
