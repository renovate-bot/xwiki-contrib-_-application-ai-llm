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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.xwiki.contrib.llm.mcp.MCPSourceText;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiAttachment;

/**
 * Shared attachment-path plumbing of the attachment-aware MCP tools ({@link MCPGetAttachmentTool} and the
 * {@code Attachments:} header line of {@link MCPGetDocumentTool}): the text-mimetype decision, human-readable
 * sizes, the fragment-guarded attachment listings and the budgeted line-oriented text-content read. Not a
 * component: a plain holder of static helpers, kept in this module so the oldcore types it handles
 * ({@link XWikiContext}, {@link XWikiAttachment}) stay out of the API module's surface, and so the
 * stream, charset and formatting dependencies it owns do not count against the composing tools' fan-out.
 *
 * @version $Id$
 * @since 0.9.1
 */
final class MCPAttachmentSupport
{
    /**
     * One binary size unit step, shared with the callers that decide whether the exact byte count adds
     * information over {@link #humanSize(long)}'s output (below one step the human form IS the byte count).
     */
    static final long ONE_KILOBYTE = 1024;

    /**
     * Cap on the attachments named in one listing ({@link #attachmentsHeaderLine(List, XWikiContext)} and
     * {@link #attachmentNamesList(List)}); the remainder is summarized as a {@code +K more} tail.
     */
    private static final int MAX_LISTED_ATTACHMENTS = 10;

    /**
     * Separator of the {@code Attachments:} header line entries.
     */
    private static final String ENTRY_SEPARATOR = " · ";

    /**
     * Separator of the comma-joined name listings.
     */
    private static final String COMMA_SEPARATOR = ", ";

    /**
     * Tail of the {@code +K more} summaries of a capped listing.
     */
    private static final String MORE_SUFFIX = " more";

    /**
     * Opens the {@code +K more} summaries of a capped listing.
     */
    private static final String PLUS = "+";

    /**
     * Separates a size value from its unit name.
     */
    private static final String UNIT_SEPARATOR = " ";

    /**
     * The mimetype prefix every text type carries.
     */
    private static final String TEXT_PREFIX = "text/";

    /**
     * The mimetype prefix of the application types inspected for text-bearing suffix forms.
     */
    private static final String APPLICATION_PREFIX = "application/";

    /**
     * The exact application mimetypes treated as text despite their non-{@code text/*} top-level type.
     */
    private static final Set<String> TEXT_APPLICATION_TYPES = Set.of(
        APPLICATION_PREFIX + "json",
        APPLICATION_PREFIX + "xml",
        APPLICATION_PREFIX + "javascript",
        APPLICATION_PREFIX + "x-javascript",
        APPLICATION_PREFIX + "yaml",
        APPLICATION_PREFIX + "x-yaml",
        APPLICATION_PREFIX + "x-sh");

    /**
     * The structured-syntax suffix marking a JSON-based application mimetype as text.
     */
    private static final String JSON_SUFFIX = "+json";

    /**
     * The structured-syntax suffix marking an XML-based application mimetype as text.
     */
    private static final String XML_SUFFIX = "+xml";

    /**
     * The date pattern of the header {@code Date:} lines.
     */
    private static final String DATE_PATTERN = "yyyy-MM-dd HH:mm";

    /**
     * Hard cap on the characters of ONE content line held in memory during a read: one char beyond the
     * shared output budget is enough to detect an over-budget line, and everything past the cap is
     * drained without being stored. This is what keeps the heap bounded on a newline-less file (a
     * minified JSON or a one-line log served with a text mimetype), which would otherwise be
     * materialized whole by a naive line read before any budget logic could run.
     */
    private static final int HARD_LINE_CAP = MCPSourceText.MAX_OUTPUT_CHARS + 1;

    /**
     * The scaled-size value from which the display rounds to the size of the next unit, so the
     * formatted output promotes instead of showing e.g. {@code 1024 KB}: at one decimal (or the
     * integer rounding from 10 up), any value from here formats as 1024 in its own unit.
     */
    private static final double UNIT_PROMOTION_THRESHOLD = 1023.5;

    private MCPAttachmentSupport()
    {
    }

    /**
     * Decides whether an attachment's mimetype designates text content that can be inlined into a tool
     * response: any {@code text/*} type, the exact application types of
     * {@link #TEXT_APPLICATION_TYPES}, and any {@code application/*+json} or {@code application/*+xml}
     * structured-syntax form. Case-insensitive; a null or blank mimetype is not text.
     *
     * @param mimeType the attachment mimetype, possibly {@code null}
     * @return whether the mimetype designates inlineable text
     */
    static boolean isTextMimeType(String mimeType)
    {
        if (StringUtils.isBlank(mimeType)) {
            return false;
        }
        String normalized = mimeType.toLowerCase(Locale.ROOT);
        if (normalized.startsWith(TEXT_PREFIX) || TEXT_APPLICATION_TYPES.contains(normalized)) {
            return true;
        }
        return normalized.startsWith(APPLICATION_PREFIX)
            && (normalized.endsWith(JSON_SUFFIX) || normalized.endsWith(XML_SUFFIX));
    }

    /**
     * Formats a byte count for humans: {@code 760 bytes} below one KB, then KB/MB/GB with one decimal
     * only while the value is below 10 in its unit ({@code 1.2 MB}) and none above ({@code 15 MB}); a
     * whole value drops the decimal even below 10 ({@code 3 KB}), and a value whose display would round
     * to 1024 in its unit promotes to the next one ({@code 1 MB}, never {@code 1024 KB}). A negative
     * count (the stored size can be unknown, reported as -1) formats as {@code unknown size}.
     *
     * @param bytes the byte count, negative when unknown
     * @return the human-readable size
     */
    static String humanSize(long bytes)
    {
        if (bytes < 0) {
            return "unknown size";
        }
        if (bytes < ONE_KILOBYTE) {
            return bytes + " bytes";
        }
        double value = (double) bytes / ONE_KILOBYTE;
        if (value < UNIT_PROMOTION_THRESHOLD) {
            return scaled(value, "KB");
        }
        value /= ONE_KILOBYTE;
        if (value < UNIT_PROMOTION_THRESHOLD) {
            return scaled(value, "MB");
        }
        return scaled(value / ONE_KILOBYTE, "GB");
    }

    /**
     * Builds the {@code Attachments:} header line of a document read: each attachment's fragment-guarded
     * filename with its human-readable size and mimetype, capped at {@link #MAX_LISTED_ATTACHMENTS}
     * entries with a {@code +K more} tail.
     *
     * @param attachments the document's attachments
     * @param xcontext the XWiki context, for the mimetype resolution
     * @return the header line, or {@code null} when the document has no attachments (the caller omits
     *     the line)
     */
    static String attachmentsHeaderLine(List<XWikiAttachment> attachments, XWikiContext xcontext)
    {
        if (CollectionUtils.isEmpty(attachments)) {
            return null;
        }
        StringBuilder line = new StringBuilder("Attachments: ");
        int shown = Math.min(attachments.size(), MAX_LISTED_ATTACHMENTS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                line.append(ENTRY_SEPARATOR);
            }
            XWikiAttachment attachment = attachments.get(i);
            line.append(MCPTextGuards.fragment(attachment.getFilename()))
                .append(" (").append(humanSize(attachment.getLongSize())).append(COMMA_SEPARATOR)
                .append(MCPTextGuards.fragment(attachment.getMimeType(xcontext))).append(')');
        }
        if (attachments.size() > shown) {
            line.append(ENTRY_SEPARATOR).append(PLUS).append(attachments.size() - shown).append(MORE_SUFFIX);
        }
        return line.toString();
    }

    /**
     * Builds the comma-joined, fragment-guarded filename listing of the missing-attachment refusal,
     * capped at {@link #MAX_LISTED_ATTACHMENTS} names with a {@code +K more} tail.
     *
     * @param attachments the document's attachments, not empty
     * @return the joined listing
     */
    static String attachmentNamesList(List<XWikiAttachment> attachments)
    {
        StringBuilder names = new StringBuilder();
        int shown = Math.min(attachments.size(), MAX_LISTED_ATTACHMENTS);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                names.append(COMMA_SEPARATOR);
            }
            names.append(MCPTextGuards.fragment(attachments.get(i).getFilename()));
        }
        if (attachments.size() > shown) {
            names.append(COMMA_SEPARATOR).append(PLUS).append(attachments.size() - shown).append(MORE_SUFFIX);
        }
        return names.toString();
    }

    /**
     * Formats an attachment date for the header {@code Date:} line ({@code yyyy-MM-dd HH:mm}, server
     * time zone).
     *
     * @param date the attachment date
     * @return the formatted date
     */
    static String formatDate(Date date)
    {
        return new SimpleDateFormat(DATE_PATTERN, Locale.ROOT).format(date);
    }

    /**
     * Streams a window of an attachment's text content: skips {@code offset} lines without materializing
     * them, then emits lines until adding the next one would exceed {@code budget} characters. A single
     * line longer than the window is hard-cut mid-line at the budget and drained (see
     * {@link TextWindow#lineCut()}), so the heap held per line never exceeds {@link #HARD_LINE_CAP} on
     * any path, skip phase included - a newline-less file cannot be materialized whole. The content is
     * decoded with the attachment's declared charset, falling back to UTF-8 when it is blank or unknown;
     * undecodable bytes are substituted by the reader's replacement character rather than failing the
     * read. The stream is fully managed here (opened and closed per call), so a caller never holds
     * attachment content it did not emit.
     *
     * @param attachment the attachment to read
     * @param xcontext the XWiki context, for the content stream
     * @param offset the number of lines to skip
     * @param budget the character budget of the emitted window
     * @return the read outcome: the window with a continuation offset when more lines remain, the full
     *     remaining content when the stream ended within budget, or the beyond-the-end marker with the
     *     total line count when {@code offset} skipped past the last line
     * @throws XWikiException when the content stream cannot be opened
     * @throws IOException when reading the content fails
     */
    static TextWindow readTextWindow(XWikiAttachment attachment, XWikiContext xcontext, int offset, int budget)
        throws XWikiException, IOException
    {
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(attachment.getContentInputStream(xcontext), charsetOf(attachment)))) {
            int skipped = 0;
            while (skipped < offset) {
                // Cap 0: drain the skipped line while storing nothing, so skipping stays heap-bounded too.
                if (readBoundedLine(reader, 0) == null) {
                    return TextWindow.beyondEnd(skipped);
                }
                skipped++;
            }
            return emitWindow(reader, offset, budget);
        }
    }

    /**
     * Emits the window lines after the skip phase of
     * {@link #readTextWindow(XWikiAttachment, XWikiContext, int, int)}: appends lines while they fit the
     * budget, and classifies the outcome (beyond-the-end, full content, truncated at a line boundary, or
     * hard-cut mid-line when the window's first line alone exceeds the budget - that line's tail is
     * already drained, so the continuation offset names the NEXT line).
     *
     * @param reader the positioned reader
     * @param offset the number of lines already skipped
     * @param budget the character budget of the emitted window
     * @return the read outcome
     * @throws IOException when reading the content fails
     */
    private static TextWindow emitWindow(BufferedReader reader, int offset, int budget) throws IOException
    {
        BoundedLine line = readBoundedLine(reader, HARD_LINE_CAP);
        if (line == null && offset > 0) {
            // Every stored line was consumed by the skip phase: the offset points exactly past the end.
            return TextWindow.beyondEnd(offset);
        }
        StringBuilder content = new StringBuilder();
        int emitted = 0;
        while (line != null) {
            if (emitted == 0 && (line.cut() || line.text().length() > budget)) {
                // The window's first line alone exceeds the budget (a cap-cut line always does, the cap
                // being above every possible budget): emit its head and continue at the next line.
                return TextWindow.cutLine(line.text().substring(0, Math.min(budget, line.text().length())),
                    offset + 1);
            }
            if (emitted > 0) {
                if (content.length() + 1 + line.text().length() > budget) {
                    return TextWindow.truncated(content.toString(), offset + emitted);
                }
                content.append('\n');
            }
            content.append(line.text());
            emitted++;
            line = readBoundedLine(reader, HARD_LINE_CAP);
        }
        return TextWindow.full(content.toString());
    }

    /**
     * Reads one line while holding at most {@code cap} of its characters: the head up to the cap is
     * stored, the tail is drained char by char (the reader buffers underneath) so the line count stays
     * correct without the heap growing with the line. Line terminators follow the usual reader
     * convention: LF, CR or CRLF each end one line and are not part of its text.
     *
     * @param reader the positioned reader
     * @param cap the maximum characters of the line to store
     * @return the bounded line, or {@code null} when the stream is at its end
     * @throws IOException when reading the content fails
     */
    private static BoundedLine readBoundedLine(BufferedReader reader, int cap) throws IOException
    {
        int c = reader.read();
        if (c == -1) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        boolean cut = false;
        while (c != -1 && c != '\n') {
            if (c == '\r') {
                swallowFollowingLineFeed(reader);
                break;
            }
            if (text.length() < cap) {
                text.append((char) c);
            } else {
                cut = true;
            }
            c = reader.read();
        }
        return new BoundedLine(text.toString(), cut);
    }

    /**
     * Consumes the LF of a CRLF pair after its CR ended a line, leaving any other character in place.
     *
     * @param reader the positioned reader
     * @throws IOException when reading the content fails
     */
    private static void swallowFollowingLineFeed(BufferedReader reader) throws IOException
    {
        reader.mark(1);
        if (reader.read() != '\n') {
            reader.reset();
        }
    }

    /**
     * Resolves the charset an attachment's text content is decoded with: the attachment's declared
     * charset when present and known to the JVM, UTF-8 otherwise (a stored charset is wiki data and must
     * not fail the read).
     *
     * @param attachment the attachment to read
     * @return the charset to decode with
     */
    private static Charset charsetOf(XWikiAttachment attachment)
    {
        String charset = attachment.getCharset();
        if (StringUtils.isBlank(charset)) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(charset);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * Formats a scaled size value with its unit: one decimal while the value is below 10 (dropping a
     * whole value's {@code .0}), no decimal from 10 up.
     *
     * @param value the size in the unit
     * @param unit the unit name
     * @return the formatted size
     */
    private static String scaled(double value, String unit)
    {
        if (value < 10) {
            String formatted = String.format(Locale.ROOT, "%.1f", value);
            formatted = StringUtils.removeEnd(formatted, ".0");
            return formatted + UNIT_SEPARATOR + unit;
        }
        return Math.round(value) + UNIT_SEPARATOR + unit;
    }

    /**
     * The outcome of a budgeted text-content read (see
     * {@link MCPAttachmentSupport#readTextWindow(XWikiAttachment, XWikiContext, int, int)}).
     *
     * @param content the emitted window content, empty on a beyond-the-end read
     * @param truncated whether more content remains after the window
     * @param lineCut whether the window is the head of a single line longer than the budget, cut
     *     mid-line ({@code truncated} then holds too, and {@code nextOffset} names the NEXT line - the
     *     cut line's tail was drained, not buffered)
     * @param nextOffset the offset continuing a truncated read, meaningful only when {@code truncated}
     * @param beyondEnd whether the requested offset skipped past the last line
     * @param totalLines the attachment's total line count, meaningful only when {@code beyondEnd}
     * @version $Id$
     */
    record TextWindow(String content, boolean truncated, boolean lineCut, int nextOffset, boolean beyondEnd,
        int totalLines)
    {
        /**
         * @param content the full remaining content
         * @return the outcome of a read that ended within budget
         */
        static TextWindow full(String content)
        {
            return new TextWindow(content, false, false, 0, false, 0);
        }

        /**
         * @param content the emitted window content
         * @param nextOffset the offset continuing the read
         * @return the outcome of a read cut at a line boundary at the budget
         */
        static TextWindow truncated(String content, int nextOffset)
        {
            return new TextWindow(content, true, false, nextOffset, false, 0);
        }

        /**
         * @param content the head of the over-budget line, cut at the budget
         * @param nextOffset the offset of the line after the cut one
         * @return the outcome of a window whose first line alone exceeds the budget
         */
        static TextWindow cutLine(String content, int nextOffset)
        {
            return new TextWindow(content, true, true, nextOffset, false, 0);
        }

        /**
         * @param totalLines the attachment's total line count
         * @return the outcome of an offset that skipped past the last line
         */
        static TextWindow beyondEnd(int totalLines)
        {
            return new TextWindow("", false, false, 0, true, totalLines);
        }
    }

    /**
     * One line as read by {@link MCPAttachmentSupport#readBoundedLine}: the stored head and whether a
     * tail beyond the cap was drained.
     *
     * @param text the stored head of the line, at most the read's cap
     * @param cut whether the line went on beyond the cap
     * @version $Id$
     */
    private record BoundedLine(String text, boolean cut)
    {
    }
}
