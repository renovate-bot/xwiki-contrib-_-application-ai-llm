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

package org.xwiki.contrib.llm;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.xwiki.component.annotation.Component;
import org.xwiki.contrib.llm.internal.DocumentIndexer;
import org.xwiki.index.TaskConsumer;
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * This class is responsible for consuming the indexing tasks and indexing the documents.
 * 
 * @version $Id$
 */
@Component
@Singleton
@Named(IndexTaskConsumer.NAME)
public class IndexTaskConsumer implements TaskConsumer
{
    /**
     * The name of the task consumer.
     */
    public static final String NAME = "indexing";

    @Inject
    private DocumentIndexer documentIndexer;

    @Inject
    private Logger logger;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public void consume(DocumentReference documentReference, String version)
    {
        try {
            XWikiDocument xdocument = this.contextProvider.get().getWiki()
                                        .getDocument(documentReference, this.contextProvider.get());
            BaseObject documentObject = xdocument.getXObject(Document.XCLASS_REFERENCE);

            String docID = documentObject.getStringValue("id");
            String docCollection = documentObject.getStringValue("collection");
            String wiki = xdocument.getDocumentReference().getWikiReference().getName();

            this.logger.info("Processing document: {}", docID);
            this.documentIndexer.indexDocument(wiki, docCollection, docID);
        } catch (Exception e) {
            this.logger.error("Error while processing document [{}]: [{}]", documentReference, e.getMessage());
        }
    }
}

