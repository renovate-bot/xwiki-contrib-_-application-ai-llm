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
package org.xwiki.contrib.llm.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.context.Execution;
import org.xwiki.contrib.llm.GPTAPIPrompt;
import org.xwiki.contrib.llm.GPTAPIPromptDBProvider;
import org.xwiki.model.EntityType;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.query.QueryManager;
import org.xwiki.stability.Unstable;
import javax.inject.Singleton;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.web.Utils;

import org.xwiki.query.Query;

@Component
@Unstable
@Singleton
public class DefaultGPTAPIPromptDBProvider implements GPTAPIPromptDBProvider {

    protected Logger logger = LoggerFactory.getLogger(DefaultGPTAPIPromptDBProvider.class);

    public DefaultGPTAPIPromptDBProvider() {
        super();
    }

    @Override
    public Map<String, GPTAPIPrompt> getPromptDB(String promptName) {
        Map<String, GPTAPIPrompt> promptDBMap = new HashMap<>();
        try {
            Execution execution = Utils.getComponent(Execution.class);
            XWikiContext context = (XWikiContext) execution.getContext().getProperty("xwikicontext");
            com.xpn.xwiki.XWiki xwiki = context.getWiki();
            QueryManager queryManager = Utils.getComponent(QueryManager.class);
            // Changed the HQL query to select the full document name
            String hql = "select doc.fullName from XWikiDocument as doc, BaseObject as obj where obj.name=doc.fullName and obj.className='AI.PromptDB.Code.PromptDBClass'";
            Query query = queryManager.createQuery(hql, Query.HQL);
            // The query will return a list of document names instead of objects
            List<String> documentNames = query.execute();
            // get rid of this doc since it is a template, it cause crash.
            documentNames.remove("AI.PromptDB.Code.PromptDBTemplate");
            logger.info("documentName: " + documentNames);
            // Check if the query returned an empty result
            if (documentNames.isEmpty())
                throw new Exception("The Query for prompt object returned an empty result.");

            // Iterate over all documents that contain an object of the class
            // 'AI.PromptDB.Code.PromptDBClass'
            for (String documentName : documentNames) {
                logger.info("doc name : " + documentName);
                XWikiDocument doc = xwiki.getDocument(documentName, context);
                // Get the objects of the class 'AI.PromptDB.Code.PromptDBClass' from the
                // current document

                if (doc != null) {
                    BaseObject object = doc.getObject("AI.PromptDB.Code.PromptDBClass");

                    if (object != null) {
                        logger.info("title of the doc : {}", doc.getTitle());
                        logger.info("prompt wanted : {}", promptName);
                        if (!documentName.equals(promptName))
                            continue;
                        else {
                            Map<String, Object> dbObjMap = new HashMap<>();
                            Collection<BaseProperty> fields = object.getFieldList();
                            for (BaseProperty field : fields) {
                                logger.info("Field: " + field.getValue());
                                dbObjMap.put(field.getName(), field.getValue());
                            }
                            dbObjMap.put("title1", doc.getTitle());
                            for (Map.Entry<String, Object> entry : dbObjMap.entrySet()) {
                                logger.info(entry.getKey() + ": " + entry.getValue());
                            }
                            if (!dbObjMap.isEmpty()) {
                                GPTAPIPrompt res = new GPTAPIPrompt(dbObjMap);
                                if (res.getName() == null || res.getPrompt() == null || res.getIsActive() == null) {
                                    logger.info("one of the value in the prompt object is null.");
                                } else
                                    promptDBMap.put(res.getName().toLowerCase(), res);
                            }
                            break;
                        }
                    }

                }
            }
            return promptDBMap;

        } catch (Exception e) {
            logger.error("Error trying to access the prompt database :", e);
            return promptDBMap;
        }
    }

}