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

import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiAttachment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the pure helpers of {@link MCPAttachmentSupport}.
 *
 * @version $Id$
 */
class MCPAttachmentSupportTest
{
    private final XWikiContext xcontext = mock(XWikiContext.class);

    private XWikiAttachment attachment(String filename, long size, String mimeType)
    {
        XWikiAttachment attachment = mock(XWikiAttachment.class);
        when(attachment.getFilename()).thenReturn(filename);
        when(attachment.getLongSize()).thenReturn(size);
        when(attachment.getMimeType(this.xcontext)).thenReturn(mimeType);
        return attachment;
    }

    @Test
    void humanSizeFormatsAcrossUnitBoundaries()
    {
        assertEquals("759 bytes", MCPAttachmentSupport.humanSize(759));
        assertEquals("1023 bytes", MCPAttachmentSupport.humanSize(1023));
        assertEquals("1 KB", MCPAttachmentSupport.humanSize(1024));
        assertEquals("3 KB", MCPAttachmentSupport.humanSize(3072));
        assertEquals("24 KB", MCPAttachmentSupport.humanSize(24 * 1024));
        assertEquals("1.2 MB", MCPAttachmentSupport.humanSize(1234567));
        assertEquals("15 MB", MCPAttachmentSupport.humanSize(15 * 1024 * 1024));
        assertEquals("5 GB", MCPAttachmentSupport.humanSize(5L * 1024 * 1024 * 1024));
        // A value whose display would round to 1024 in its unit promotes to the next unit instead.
        assertEquals("1023 KB", MCPAttachmentSupport.humanSize(1048063));
        assertEquals("1 MB", MCPAttachmentSupport.humanSize(1048570));
        assertEquals("unknown size", MCPAttachmentSupport.humanSize(-1));
    }

    @Test
    void isTextMimeTypeMatrix()
    {
        assertTrue(MCPAttachmentSupport.isTextMimeType("text/plain"));
        assertTrue(MCPAttachmentSupport.isTextMimeType("TEXT/PLAIN"));
        assertTrue(MCPAttachmentSupport.isTextMimeType("text/x-anything"));
        assertTrue(MCPAttachmentSupport.isTextMimeType("application/json"));
        assertTrue(MCPAttachmentSupport.isTextMimeType("application/xml"));
        assertTrue(MCPAttachmentSupport.isTextMimeType("application/javascript"));
        assertTrue(MCPAttachmentSupport.isTextMimeType("application/x-javascript"));
        assertTrue(MCPAttachmentSupport.isTextMimeType("application/yaml"));
        assertTrue(MCPAttachmentSupport.isTextMimeType("application/x-yaml"));
        assertTrue(MCPAttachmentSupport.isTextMimeType("application/x-sh"));
        assertTrue(MCPAttachmentSupport.isTextMimeType("application/ld+json"));
        assertTrue(MCPAttachmentSupport.isTextMimeType("application/xhtml+xml"));
        // SVG is XML text an agent can read and edit; it is deliberately NOT routed as a vision image.
        assertTrue(MCPAttachmentSupport.isTextMimeType("image/svg+xml"));
        // RTF's text/* form is a control-word format that reads terribly raw: routed to extraction.
        assertFalse(MCPAttachmentSupport.isTextMimeType("text/rtf"));
        assertFalse(MCPAttachmentSupport.isTextMimeType("application/pdf"));
        assertFalse(MCPAttachmentSupport.isTextMimeType("application/zip"));
        assertFalse(MCPAttachmentSupport.isTextMimeType("image/png"));
        assertFalse(MCPAttachmentSupport.isTextMimeType(null));
        assertFalse(MCPAttachmentSupport.isTextMimeType(" "));
    }

    @Test
    void isExtractableMimeTypeMatrix()
    {
        assertTrue(MCPAttachmentSupport.isExtractableMimeType("application/pdf"));
        assertTrue(MCPAttachmentSupport.isExtractableMimeType("APPLICATION/PDF"));
        assertTrue(MCPAttachmentSupport.isExtractableMimeType("application/rtf"));
        assertTrue(MCPAttachmentSupport.isExtractableMimeType("text/rtf"));
        assertTrue(MCPAttachmentSupport.isExtractableMimeType("application/msword"));
        assertTrue(MCPAttachmentSupport.isExtractableMimeType("application/vnd.ms-excel"));
        assertTrue(MCPAttachmentSupport.isExtractableMimeType("application/vnd.ms-powerpoint"));
        assertTrue(MCPAttachmentSupport.isExtractableMimeType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertTrue(MCPAttachmentSupport.isExtractableMimeType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertTrue(MCPAttachmentSupport.isExtractableMimeType("application/vnd.oasis.opendocument.text"));
        assertTrue(MCPAttachmentSupport.isExtractableMimeType("application/vnd.oasis.opendocument.presentation"));
        assertFalse(MCPAttachmentSupport.isExtractableMimeType("application/zip"));
        assertFalse(MCPAttachmentSupport.isExtractableMimeType("text/plain"));
        assertFalse(MCPAttachmentSupport.isExtractableMimeType("image/png"));
        assertFalse(MCPAttachmentSupport.isExtractableMimeType(null));
        assertFalse(MCPAttachmentSupport.isExtractableMimeType(" "));
    }

    @Test
    void isInlineableImageMimeTypeMatrix()
    {
        assertTrue(MCPAttachmentSupport.isInlineableImageMimeType("image/png"));
        assertTrue(MCPAttachmentSupport.isInlineableImageMimeType("IMAGE/PNG"));
        assertTrue(MCPAttachmentSupport.isInlineableImageMimeType("image/jpeg"));
        assertTrue(MCPAttachmentSupport.isInlineableImageMimeType("image/gif"));
        assertTrue(MCPAttachmentSupport.isInlineableImageMimeType("image/webp"));
        assertFalse(MCPAttachmentSupport.isInlineableImageMimeType("image/tiff"));
        assertFalse(MCPAttachmentSupport.isInlineableImageMimeType("image/bmp"));
        assertFalse(MCPAttachmentSupport.isInlineableImageMimeType("image/svg+xml"));
        assertFalse(MCPAttachmentSupport.isInlineableImageMimeType("application/pdf"));
        assertFalse(MCPAttachmentSupport.isInlineableImageMimeType(null));
        assertFalse(MCPAttachmentSupport.isInlineableImageMimeType(" "));
        // The broader image predicate backs the image-specific refusal wording of non-inlineable types.
        assertTrue(MCPAttachmentSupport.isImageMimeType("image/tiff"));
        assertFalse(MCPAttachmentSupport.isImageMimeType("application/pdf"));
        assertFalse(MCPAttachmentSupport.isImageMimeType(null));
    }

    @Test
    void attachmentsHeaderLineIsNullOnEmptyList()
    {
        assertNull(MCPAttachmentSupport.attachmentsHeaderLine(List.of(), this.xcontext));
        assertNull(MCPAttachmentSupport.attachmentsHeaderLine(null, this.xcontext));
    }

    @Test
    void attachmentsHeaderLineFormatsSizeAndMimetypePerEntry()
    {
        List<XWikiAttachment> attachments = List.of(
            attachment("report.pdf", 1234567, "application/pdf"),
            attachment("logo.png", 24 * 1024, "image/png"));

        String line = MCPAttachmentSupport.attachmentsHeaderLine(attachments, this.xcontext);

        assertEquals("Attachments: report.pdf (1.2 MB, application/pdf) · logo.png (24 KB, image/png)", line);
    }

    @Test
    void attachmentsHeaderLineCapsAtTenEntriesWithMoreTail()
    {
        List<XWikiAttachment> attachments = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            attachments.add(attachment("file" + i + ".txt", 100, "text/plain"));
        }

        String line = MCPAttachmentSupport.attachmentsHeaderLine(attachments, this.xcontext);

        assertTrue(line.contains("file9.txt"), line);
        assertFalse(line.contains("file10.txt"), line);
        assertTrue(line.endsWith(" · +2 more"), line);
    }

    @Test
    void attachmentsHeaderLineNeutralizesHostileFilenames()
    {
        List<XWikiAttachment> attachments =
            List.of(attachment("evil\nname" + "x".repeat(250) + ".txt", 100, "text/plain"));

        String line = MCPAttachmentSupport.attachmentsHeaderLine(attachments, this.xcontext);

        assertTrue(line.startsWith("Attachments: evilname"), line);
        assertFalse(line.contains("\n"), line);
        assertTrue(line.contains("…"), line);
    }

    @Test
    void attachmentNamesListCapsAtTenNamesWithMoreTail()
    {
        List<XWikiAttachment> attachments = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            attachments.add(attachment("file" + i + ".txt", 100, "text/plain"));
        }

        String names = MCPAttachmentSupport.attachmentNamesList(attachments);

        assertTrue(names.startsWith("file0.txt, file1.txt"), names);
        assertFalse(names.contains("file10.txt"), names);
        assertTrue(names.endsWith(", +2 more"), names);
    }

    @Test
    void formatDateUsesIsoLikeMinutePrecision()
    {
        assertEquals("2026-08-12 14:03", MCPAttachmentSupport.formatDate(
            new GregorianCalendar(2026, Calendar.AUGUST, 12, 14, 3).getTime()));
    }
}
