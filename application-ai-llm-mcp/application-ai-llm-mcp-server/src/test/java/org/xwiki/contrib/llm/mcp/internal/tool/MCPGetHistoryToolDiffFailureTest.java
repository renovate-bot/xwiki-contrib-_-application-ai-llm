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

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.diff.DiffException;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.model.reference.WikiReference;
import org.xwiki.security.authorization.Right;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;
import org.xwiki.test.junit5.mockito.ComponentTest;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.xwiki.contrib.llm.mcp.internal.tool.MCPToolTestUtils.textOf;

/**
 * Tests the {@link MCPGetHistoryTool} diff-computation failure path with a MOCKED
 * {@link MCPHistorySupport}: the main test class wires the real support and real diff components,
 * which cannot be made to throw {@link DiffException} on demand.
 *
 * @version $Id$
 */
@ComponentTest
class MCPGetHistoryToolDiffFailureTest extends AbstractMCPToolTest
{
    private static final String REF = "Sandbox.WebHome";

    private static final DocumentReference DOC_REF = new DocumentReference("xwiki", "Sandbox", "WebHome");

    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @InjectMockComponents
    private MCPGetHistoryTool tool;

    @MockComponent
    private MCPDocumentAccess documentAccess;

    @MockComponent
    private DocumentAccessBridge documentAccessBridge;

    @MockComponent
    private EntityReferenceSerializer<String> serializer;

    @MockComponent
    private MCPWikiReach wikiReach;

    @MockComponent
    private MCPHistorySupport historySupport;

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.wikiReach.resolveSingleWiki(any())).thenReturn("xwiki");
        when(this.documentAccess.resolveAndAuthorize(anyString(), eq(Right.VIEW), any(WikiReference.class)))
            .thenReturn(DOC_REF);
        lenient().when(this.serializer.serialize(any())).thenReturn("xwiki:Sandbox.WebHome");
        lenient().when(this.wikiReach.isReachEnabled()).thenReturn(true);
    }

    @Override
    protected MCPTool getTool()
    {
        return this.tool;
    }

    @Test
    void diffComputationFailureSurfacesAsAnErrorResultWithTheCauseInTheLogs() throws Exception
    {
        XWikiDocument doc = mock(XWikiDocument.class);
        lenient().when(doc.getVersion()).thenReturn("3.4");
        lenient().when(doc.getContent()).thenReturn("current body");
        when(this.documentAccessBridge.exists(DOC_REF)).thenReturn(true);
        when(this.documentAccessBridge.getDocumentInstance(DOC_REF)).thenReturn(doc);
        XWikiDocument fromDoc = mock(XWikiDocument.class);
        when(fromDoc.getContent()).thenReturn("old body");
        when(this.historySupport.loadRevision(DOC_REF, "2.1")).thenReturn(fromDoc);
        when(this.historySupport.unifiedDiffBlocks(anyString(), anyString()))
            .thenThrow(new DiffException("differentiation failed"));

        McpSchema.CallToolResult result = call(Map.of("reference", REF, "from", "2.1"));

        assertEquals(Boolean.TRUE, result.isError());
        assertTrue(textOf(result).startsWith("Could not compute the diff."), textOf(result));
        assertTrue(this.logCapture.getMessage(0).startsWith("MCP get_history tool failed to diff"),
            this.logCapture.getMessage(0));
    }
}
