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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.xwiki.bridge.DocumentAccessBridge;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.mcp.MCPAccessDeniedException;
import org.xwiki.contrib.llm.mcp.MCPDocumentAccess;
import org.xwiki.contrib.llm.mcp.MCPReachAwareParams;
import org.xwiki.contrib.llm.mcp.MCPSourceText;
import org.xwiki.contrib.llm.mcp.MCPTool;
import org.xwiki.contrib.llm.mcp.MCPToolSupport;
import org.xwiki.contrib.llm.mcp.MCPWikiReach;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiAttachment;
import com.xpn.xwiki.doc.XWikiDocument;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * MCP tool that reads one attachment of an XWiki document for an agent: text attachments are inlined
 * under the shared output budget with offset continuation, while every other type returns the metadata
 * header with a download URL instead of content.
 *
 * <p>The attachment is addressed by its EXACT stored filename (as listed by {@code get_document}'s
 * {@code Attachments:} header line); the platform's fuzzy filename fallback is deliberately not used, so
 * a miss teaches the real names instead of silently serving a similarly-named file.</p>
 *
 * <p>Resolution and authorization both go through {@link MCPDocumentAccess#resolveAndAuthorize(String,
 * Right)} for {@link Right#VIEW} before the document is loaded, so the per-wiki space filter is applied
 * and the existence of a protected document is never leaked.</p>
 *
 * @version $Id$
 * @since 0.9.1
 */
@Component
@Named(MCPGetAttachmentTool.TOOL_ID)
@Singleton
public class MCPGetAttachmentTool implements MCPTool
{
    /**
     * The stable tool identifier. Used as the XWiki component hint.
     */
    public static final String TOOL_ID = "get_attachment";

    private static final String REFERENCE_PARAM = "reference";

    private static final String FILENAME_PARAM = "filename";

    private static final String OFFSET_PARAM = "offset";

    private static final String METADATA_PARAM = "metadata";

    private static final String NEW_LINE = "\n";

    private static final String DOUBLE_NEW_LINE = "\n\n";

    private static final String QUOTE = "\"";

    private static final String PERIOD = ".";

    /**
     * The agent-facing message shared by the existence-check and load failures, mirroring the
     * {@code get_document} wording so a broken document reads the same through both tools.
     */
    private static final String COULD_NOT_READ_PREFIX = "Could not read the document ";

    /**
     * The agent-facing message of a failed content read; the root cause stays in the server logs.
     */
    private static final String CONTENT_READ_ERROR = "Could not read the attachment content.";

    /**
     * The offset-assignment fragment closing both continuation notes, directly followed by the offset
     * value.
     */
    private static final String OFFSET_EQUALS = OFFSET_PARAM + "=";

    /**
     * Opens the truncation note of a text read cut at a line boundary at the output budget, completed by
     * the continuation offset; the wording follows the {@code get_document} continuation note.
     */
    private static final String CONTINUATION_PREFIX = "Output truncated at the ~"
        + MCPSourceText.MAX_OUTPUT_TOKENS + "-token cap; continue with " + OFFSET_EQUALS;

    /**
     * Opens the truncation note of a window whose first line alone exceeds the output budget and was cut
     * MID-LINE, completed by the continuation offset - which names the NEXT line, the only line-addressed
     * continuation an offset parameter can express (the cut line's tail is drained, never buffered).
     */
    private static final String LINE_CUT_CONTINUATION_PREFIX = "Output truncated MID-LINE at the ~"
        + MCPSourceText.MAX_OUTPUT_TOKENS + "-token cap (single line longer than the budget); the rest of "
        + "this line is not retrievable by offset - continue with the next line at " + OFFSET_EQUALS;

    /**
     * Body of a text read whose emitted content is empty (an empty stored file).
     */
    private static final String NO_CONTENT_BODY = "Attachment has no content.";

    /**
     * The two declared-parameter variants (see {@link MCPReachAwareParams}): the local variant drops the
     * cross-wiki sentence and the wiki-prefixed reference example from the {@code reference} description
     * so no cross-wiki capability is surfaced.
     */
    private static final MCPReachAwareParams PARAMS = MCPReachAwareParams.of(MCPGetAttachmentTool::params);

    @Inject
    private Logger logger;

    @Inject
    private MCPDocumentAccess documentAccess;

    @Inject
    private DocumentAccessBridge documentAccessBridge;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Inject
    private MCPWikiReach wikiReach;

    /**
     * Builds the declared parameter set, using a wiki-prefixed reference example and the cross-wiki
     * sentence in the {@code reference} description only when cross-wiki reach is advertised.
     *
     * @param crossWiki whether to advertise cross-wiki reach in the {@code reference} description
     * @return the declared parameter set
     */
    private static MCPToolSupport params(boolean crossWiki)
    {
        String referenceDescription = "The document holding the attachment, e.g. \"Help.GettingStarted\" or \""
            + (crossWiki ? "xwiki:" : "") + "Sandbox.WebHome\".";
        if (crossWiki) {
            referenceDescription += MCPReachAwareParams.CROSS_WIKI_REFERENCE_SENTENCE;
        }
        return MCPToolSupport.builder()
            .requiredString(REFERENCE_PARAM, referenceDescription)
            .requiredString(FILENAME_PARAM, "Exact attachment filename, as listed by get_document.")
            .integer(OFFSET_PARAM, "Line offset into text content for continuing a truncated read. Default 0.")
            .bool(METADATA_PARAM, "Return only the metadata header and download URL, skipping content. "
                + "Default false.")
            .build();
    }

    @Override
    public McpSchema.Tool getToolDefinition()
    {
        return McpSchema.Tool.builder(TOOL_ID, PARAMS.advertised(this.wikiReach.isReachEnabled()).inputSchema())
            .description("Read an attachment from a document. Text attachments (text/*, JSON, XML, YAML, "
                + "scripts) are returned inline under the ~" + MCPSourceText.MAX_OUTPUT_TOKENS + "-token "
                + "output budget, with offset continuation for longer files; every other type returns the "
                + "metadata header with a download URL instead of content. The filename must match exactly, "
                + "as listed by get_document's Attachments header line.")
            .build();
    }

    @Override
    public String getCategory()
    {
        return "Search & Navigation";
    }

    @Override
    public String getSummary()
    {
        return "Read an attachment's content or metadata from a document.";
    }

    @Override
    public String getManPage()
    {
        return """
            NOTES
                The filename must match EXACTLY: pick it from the Attachments header line of a
                get_document read. There is no fuzzy matching - a near-miss is refused with the
                names that do exist on the document.

                Text attachments (text/*, JSON, XML, YAML, scripts) are inlined under the output
                token budget; a longer file is cut at a line boundary with a continuation note -
                pass its offset to read the next window. Every other type (images, PDFs, archives)
                returns the metadata header with a Download URL instead of content.

                metadata=true is a cheap probe: the header and Download URL only, the content is
                never read. The Document header line echoes the document version - use it as
                base_version for a follow-up write to the same document.

            EXAMPLES
                Read a text attachment:  reference="Sandbox.WebHome", filename="notes.txt"
                Metadata only:  reference="Sandbox.WebHome", filename="report.pdf", metadata=true
                Continuation:   reference="Sandbox.WebHome", filename="build.log", offset=250
                            (the offset comes from the previous read's truncation note)

            SEE ALSO
                man get_document    Lists a document's attachments (the Attachments header line) and
                                    shows the document version.
                man                 (no argument) List all tools and reference pages.
            """;
    }

    @Override
    public McpSchema.CallToolResult execute(McpSchema.CallToolRequest request)
    {
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

        try {
            return read(args);
        } catch (IllegalArgumentException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        }
    }

    /**
     * Runs one read: parses the arguments, resolves and authorizes the document, loads it, resolves the
     * exact attachment and dispatches to the response composition.
     *
     * @param args the tool call arguments
     * @return the tool result
     * @throws IllegalArgumentException with an agent-facing message on invalid arguments or a failed
     *     load
     */
    private McpSchema.CallToolResult read(Map<String, Object> args)
    {
        String reference = PARAMS.parser().requireString(args, REFERENCE_PARAM);
        String filename = PARAMS.parser().requireString(args, FILENAME_PARAM);
        int offset = PARAMS.parser().integer(args, OFFSET_PARAM, 0);
        if (offset < 0) {
            return MCPToolSupport.errorResult(MCPToolSupport.ERROR_PREFIX + OFFSET_PARAM + "' must be >= 0.");
        }
        boolean metadataOnly = PARAMS.parser().bool(args, METADATA_PARAM);

        DocumentReference ref;
        try {
            ref = this.documentAccess.resolveAndAuthorize(reference, Right.VIEW);
        } catch (MCPAccessDeniedException e) {
            return MCPToolSupport.errorResult(e.getMessage());
        }

        if (!documentExists(ref, reference)) {
            return MCPToolSupport.errorResult(
                "No such document: " + QUOTE + MCPTextGuards.fragment(reference) + QUOTE + PERIOD);
        }
        XWikiDocument xdoc = loadDocument(ref, reference);
        XWikiAttachment attachment = xdoc.getExactAttachment(filename);
        if (attachment == null) {
            return MCPToolSupport.errorResult(missingAttachmentMessage(filename, reference, xdoc));
        }
        return respond(xdoc, attachment, offset, metadataOnly);
    }

    /**
     * Builds the missing-attachment refusal, teaching the exact filenames that do exist on the document
     * (fragment-guarded: filenames are wiki-authored) so the agent can correct the call instead of
     * retrying blindly.
     *
     * @param filename the requested filename
     * @param reference the original reference string, echoed neutralized
     * @param xdoc the loaded document
     * @return the agent-facing error message
     */
    private String missingAttachmentMessage(String filename, String reference, XWikiDocument xdoc)
    {
        String message = "Attachment " + QUOTE + MCPTextGuards.fragment(filename) + QUOTE + " not found on "
            + QUOTE + MCPTextGuards.fragment(reference) + QUOTE + PERIOD + ' ';
        var attachments = xdoc.getAttachmentList();
        if (attachments.isEmpty()) {
            return message + "This document has no attachments.";
        }
        return message + "Attachments on this document: "
            + MCPAttachmentSupport.attachmentNamesList(attachments) + PERIOD;
    }

    /**
     * Composes the response: the metadata header always, then either nothing more ({@code metadata=true}),
     * the not-inlineable pointer for a non-text type, or the budgeted text content window.
     *
     * @param xdoc the loaded document
     * @param attachment the resolved attachment
     * @param offset the number of content lines to skip
     * @param metadataOnly whether to skip the content entirely
     * @return the tool result
     */
    private McpSchema.CallToolResult respond(XWikiDocument xdoc, XWikiAttachment attachment, int offset,
        boolean metadataOnly)
    {
        XWikiContext xcontext = this.contextProvider.get();
        String mimeType = attachment.getMimeType(xcontext);
        String header = composeHeader(xdoc, attachment, mimeType, xcontext);
        if (metadataOnly) {
            return MCPToolSupport.result(header);
        }
        if (!MCPAttachmentSupport.isTextMimeType(mimeType)) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + "Content is "
                + MCPTextGuards.fragment(mimeType)
                + "; not inlineable as text. Use the Download URL above.");
        }
        return textContentResult(header, attachment, xcontext, offset);
    }

    /**
     * Reads and appends the budgeted text-content window below the header, with the continuation note on
     * a truncated read and the beyond-the-end message on an offset past the last line. The header counts
     * toward the shared output budget, so the window fills only what remains under it.
     *
     * @param header the composed metadata header
     * @param attachment the resolved attachment
     * @param xcontext the XWiki context
     * @param offset the number of content lines to skip
     * @return the tool result
     */
    private McpSchema.CallToolResult textContentResult(String header, XWikiAttachment attachment,
        XWikiContext xcontext, int offset)
    {
        int budget = Math.max(1, MCPSourceText.MAX_OUTPUT_CHARS - header.length());
        MCPAttachmentSupport.TextWindow window;
        try {
            window = MCPAttachmentSupport.readTextWindow(attachment, xcontext, offset, budget);
        } catch (Exception e) {
            this.logger.warn("MCP get_attachment tool failed to read the content of [{}]: [{}]",
                attachment.getFilename(), ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_attachment tool content-read failure details", e);
            return MCPToolSupport.errorResult(CONTENT_READ_ERROR);
        }
        if (window.beyondEnd()) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + "Text content has only "
                + window.totalLines() + " lines; offset " + offset + " is beyond the end.");
        }
        if (window.truncated()) {
            String notePrefix = window.lineCut() ? LINE_CUT_CONTINUATION_PREFIX : CONTINUATION_PREFIX;
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + window.content() + NEW_LINE
                + notePrefix + window.nextOffset() + PERIOD);
        }
        if (window.content().isEmpty()) {
            return MCPToolSupport.result(header + DOUBLE_NEW_LINE + NO_CONTENT_BODY);
        }
        return MCPToolSupport.result(header + DOUBLE_NEW_LINE + window.content());
    }

    /**
     * Composes the metadata header, one field per line, every wiki-authored value neutralized
     * (fragment-guarded filename and mimetype, line-break-stripped serialized references and stored
     * strings). The
     * {@code Author:}, {@code Date:} and {@code Download:} lines are omitted when their value is
     * unavailable, mirroring how {@code get_document} omits its URL line.
     *
     * @param xdoc the loaded document
     * @param attachment the resolved attachment
     * @param mimeType the attachment's resolved mimetype
     * @param xcontext the XWiki context
     * @return the composed header
     */
    private String composeHeader(XWikiDocument xdoc, XWikiAttachment attachment, String mimeType,
        XWikiContext xcontext)
    {
        StringBuilder header = new StringBuilder();
        header.append("Attachment: ").append(MCPTextGuards.fragment(attachment.getFilename())).append(NEW_LINE);
        header.append("Document: ")
            .append(MCPToolSupport.stripLineBreaks(this.serializer.serialize(xdoc.getDocumentReference())))
            .append(" (version ").append(xdoc.getVersion()).append(')').append(NEW_LINE);
        header.append("Mimetype: ").append(MCPTextGuards.fragment(mimeType)).append(NEW_LINE);
        header.append("Size: ").append(sizeDescription(attachment.getLongSize())).append(NEW_LINE);
        header.append("Attachment version: ").append(MCPToolSupport.stripLineBreaks(attachment.getVersion()));
        if (attachment.getAuthorReference() != null) {
            header.append(NEW_LINE).append("Author: ")
                .append(MCPToolSupport.stripLineBreaks(this.serializer.serialize(
                    attachment.getAuthorReference())));
        }
        if (attachment.getDate() != null) {
            header.append(NEW_LINE).append("Date: ")
                .append(MCPAttachmentSupport.formatDate(attachment.getDate()));
        }
        String downloadUrl = safeDownloadUrl(xdoc, attachment.getFilename(), xcontext);
        if (downloadUrl != null) {
            header.append(NEW_LINE).append("Download: ").append(downloadUrl);
        }
        return header.toString();
    }

    /**
     * Formats the {@code Size:} value: the human-readable size, with the exact byte count in parentheses
     * when it adds information (from one KB up; below that the human form IS the byte count, and a
     * negative count means the stored size is unknown).
     *
     * @param bytes the attachment's byte count, negative when unknown
     * @return the formatted size value
     */
    private static String sizeDescription(long bytes)
    {
        String human = MCPAttachmentSupport.humanSize(bytes);
        if (bytes < MCPAttachmentSupport.ONE_KILOBYTE) {
            return human;
        }
        return human + " (" + bytes + " bytes)";
    }

    /**
     * Builds the external download URL of the attachment, returning {@code null} instead of propagating
     * a URL-building failure: the line is a convenience, never worth failing the read over.
     *
     * @param xdoc the loaded document
     * @param filename the attachment's stored filename
     * @param xcontext the XWiki context
     * @return the download URL, or {@code null} when it could not be built
     */
    private String safeDownloadUrl(XWikiDocument xdoc, String filename, XWikiContext xcontext)
    {
        try {
            return xdoc.getExternalAttachmentURL(filename, "download", xcontext);
        } catch (Exception e) {
            this.logger.debug("MCP get_attachment tool could not build the download URL", e);
            return null;
        }
    }

    private boolean documentExists(DocumentReference ref, String reference)
    {
        try {
            return this.documentAccessBridge.exists(ref);
        } catch (Exception e) {
            this.logger.warn("MCP get_attachment tool failed to check existence of [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_attachment tool existence-check failure details", e);
            throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE
                + MCPTextGuards.fragment(reference) + QUOTE + PERIOD);
        }
    }

    /**
     * Loads the addressed document as its oldcore instance, which owns the attachment list and the
     * attachment URL building.
     *
     * @param ref the resolved document reference
     * @param reference the original reference string, for error messages
     * @return the loaded document
     * @throws IllegalArgumentException with the agent-facing message when the load fails
     */
    private XWikiDocument loadDocument(DocumentReference ref, String reference)
    {
        Object doc = null;
        try {
            doc = this.documentAccessBridge.getDocumentInstance(ref);
        } catch (Exception e) {
            this.logger.warn("MCP get_attachment tool failed to load [{}]: [{}]", reference,
                ExceptionUtils.getRootCauseMessage(e));
            this.logger.debug("MCP get_attachment tool load failure details", e);
        }
        if (doc instanceof XWikiDocument xdoc) {
            return xdoc;
        }
        throw new IllegalArgumentException(COULD_NOT_READ_PREFIX + QUOTE
            + MCPTextGuards.fragment(reference) + QUOTE + PERIOD);
    }
}
