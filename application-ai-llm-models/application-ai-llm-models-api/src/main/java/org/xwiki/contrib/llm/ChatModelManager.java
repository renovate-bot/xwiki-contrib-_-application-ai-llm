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

import java.util.List;

import org.xwiki.component.annotation.Role;
import org.xwiki.stability.Unstable;
import org.xwiki.user.UserReference;

/**
 * Provides access to the chat models that are configured for the current wiki.
 *
 * @version $Id$
 * @since 0.3
 */
@Unstable
@Role
public interface ChatModelManager
{
    /**
     * @param name the name of the model to retrieve
     * @param userReference the user for whom to retrieve the model, used to check rights
     * @param wikiId the wiki from which to retrieve the model
     * @return the model with the given name
     */
    ChatModel getModel(String name, UserReference userReference, String wikiId) throws GPTAPIException;

    /**
     * @param userReference the user for whom to retrieve the models, used to check rights
     * @param wikiId the wiki from which to retrieve the models
     * @return a list of all configured models
     */
    List<ChatModelDescriptor> getModels(UserReference userReference, String wikiId) throws GPTAPIException;
}
