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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.function.FailableConsumer;
import org.slf4j.Logger;
import org.xwiki.contrib.llm.AbstractChatRequestFilter;
import org.xwiki.contrib.llm.CollectionManager;
import org.xwiki.contrib.llm.openai.ChatCompletionChunk;
import org.xwiki.contrib.llm.openai.ChatCompletionChunkChoice;
import org.xwiki.contrib.llm.openai.ChatCompletionRequest;
import org.xwiki.contrib.llm.openai.ChatCompletionResult;
import org.xwiki.contrib.llm.openai.ChatMessage;
import org.xwiki.contrib.llm.openai.Context;

/**
 * A filter that adds context from the given collections to the request.
 *
 * @version $Id$
 * @since 0.3
 */
public class RAGChatRequestFilter extends AbstractChatRequestFilter
{
    private static final String KEYWARD_STRING = "{{search_results}}";
    private static final String DEFAULT_CONTEXT_PROMPT = "You are an AI assistant.\n"
        + "Use the following search results to answer the user's question:\n"
        + "<documents>\n"
        + KEYWARD_STRING
        + "\n</documents>\n"
        + "If the information is not relevant, inform the user and rely on your general knowledge to answer.\n"
        + "The answer must be written in the language of the user's question formatted using markdown syntax.\n";
    private static final String URL_PLACEHOLDER = "{{url}}";
    private static final String INDEX_PLACEHOLDER = "{{index}}";
    private static final String CONTENT_PLACEHOLDER = "{{content}}";
    private static final String DEFAULT_CHUNK_TEMPLATE = """
        <document index="%s">
        <source>%s</source>
        <document_content>
        %s
        </document_content>
        </document>
        """.formatted(INDEX_PLACEHOLDER, URL_PLACEHOLDER, CONTENT_PLACEHOLDER);
    private static final String SOURCE_STRING = "%s %n";
    private static final String SIMILARITY_SEARCH_ERROR_MSG = "There was an error during similarity search";
    private static final String ERROR_LOG_FORMAT = "{}: {}";
    private static final String NL = "\n";
    private static final String NL2 = "\n\n";
    private static final String SOURCES_STRING2 = "Sources: ";
    
    private final List<String> collections;
    private final CollectionManager collectionManager;
    private final int maxSemanticResults;
    private final int maxKeywordResults;
    private final String contextPrompt;
    private final String chunkTemplate;
    private final Logger logger;

    /**
     * Constructor.
     *
     * @param collections the collections to use
     * @param collectionManager the collection manager
     * @param maxSemanticResults the maximum number of results to return
     * @param maxKeywordResults the maximum number of keyword results to return
     * @param contextPrompt the context prompt
     * @param chunkTemplate the template of a chunk in the context prompt
     * @param logger the logger
     */
    public RAGChatRequestFilter(List<String> collections,
        CollectionManager collectionManager,
        Integer maxSemanticResults,
        Integer maxKeywordResults,
        String contextPrompt,
        String chunkTemplate,
        Logger logger)
    {
        this.collections = collections;
        this.collectionManager = collectionManager;
        int initialMaxSemanticResults = maxSemanticResults != null ? maxSemanticResults : 0;
        int initialMaxKeywordResults = maxKeywordResults != null ? maxKeywordResults : 0;
        if (initialMaxKeywordResults + initialMaxSemanticResults <= 0) {
            initialMaxKeywordResults = 7;
            initialMaxSemanticResults = 3;
        }
        this.maxSemanticResults = initialMaxSemanticResults;
        this.maxKeywordResults = initialMaxKeywordResults;
        this.contextPrompt = contextPrompt;
        this.chunkTemplate = StringUtils.isBlank(chunkTemplate) ? DEFAULT_CHUNK_TEMPLATE : chunkTemplate;
        this.logger = logger;
    }

    @Override
    public void processStreaming(ChatCompletionRequest request,
        FailableConsumer<ChatCompletionChunk, IOException> consumer) throws IOException
    {
        // Get the sources from the search results cache or perform the search
        List<Context> searchResults = getSearchResults(request);

        // First, modify the request with additional context as needed
        ChatCompletionRequest modifiedRequest = addContext(request, searchResults);
        String sources = extractURLsAndformat(searchResults);

        long timestamp = System.currentTimeMillis() / 1000;

        // Set the id to a random UUID
        String id = UUID.randomUUID().toString();

        // Create and send a custom ChatCompletionChunk with the sources
        ChatMessage chatMessage = new ChatMessage("assistant", SOURCES_STRING2 + sources, searchResults);

        ChatCompletionChunkChoice choice = new ChatCompletionChunkChoice(0, chatMessage, null);
        ChatCompletionChunk sourcesResponse = new ChatCompletionChunk(id, timestamp, request.model(), List.of(choice));
        String llmCurrentMemoryState = formatMessages(modifiedRequest.messages());
        chatMessage.setMemory(llmCurrentMemoryState);
        consumer.accept(sourcesResponse);
        // Wrap the consumer to add the same timestamp and id to all responses
        FailableConsumer<ChatCompletionChunk, IOException> wrappedConsumer = response -> {
            ChatCompletionChunk wrappedResponse = new ChatCompletionChunk(id, timestamp, response.model(),
                response.choices());
            consumer.accept(wrappedResponse);
        };

        // Now, use the super implementation with the modified request
        super.processStreaming(modifiedRequest, wrappedConsumer);
    }
    

    @Override
    public ChatCompletionResult process(ChatCompletionRequest request) throws IOException
    {
        List<Context> searchResults = getSearchResults(request);
        ChatCompletionRequest modifiedRequest = addContext(request, searchResults);
        String llmCurrentMemoryState = formatMessages(modifiedRequest.messages());
        ChatCompletionResult response = super.process(modifiedRequest);
        // Get the message from the response and add the context
        if (!response.choices().isEmpty()) {
            ChatMessage message = response.choices().get(0).message();
            message.setContext(searchResults);
            // Add full memory to the message
            message.setMemory(llmCurrentMemoryState);
            // Add the sources to the content
            message.setContent(SOURCES_STRING2 + extractURLsAndformat(searchResults) + NL2 + message.getContent());
        }
        return response;
    }

    private String formatMessages(List<ChatMessage> messages)
    {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            sb.append("Role: ").append(message.getRole()).append(NL);
            sb.append("Content: ").append(message.getContent()).append(NL2);
        }
        return sb.toString();
    }

    private ChatCompletionRequest addContext(ChatCompletionRequest request, List<Context> context)
    {
        String searchResults = buildContext(context);
        String effectivePrompt = StringUtils.isBlank(this.contextPrompt) ? DEFAULT_CONTEXT_PROMPT : this.contextPrompt;
        String updatedContextPrompt;
        
        if (effectivePrompt.contains(KEYWARD_STRING)) {
            updatedContextPrompt = effectivePrompt.replace(KEYWARD_STRING, searchResults);
        } else {
            updatedContextPrompt = effectivePrompt + "\n\nRelevant information:\n" + searchResults;
        }
        
        ChatMessage systemMessage = new ChatMessage("system", updatedContextPrompt);
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        messages.add(0, systemMessage);
    
        return request.but()
            .messages(messages)
            .build();
    }

    private String buildContext(List<Context> searchResults)
    {
        if (searchResults.isEmpty()) {
            return "No similar content found.";
        }

        StringBuilder contextBuilder = new StringBuilder();
        int index = 0;
        for (Context result : searchResults) {
            ++index;
            String chunk = this.chunkTemplate
                .replace(URL_PLACEHOLDER, result.url())
                .replace(CONTENT_PLACEHOLDER, result.content())
                .replace(INDEX_PLACEHOLDER, String.valueOf(index));
            contextBuilder.append(chunk);
            // If the last character isn't a newline, append one.
            if (!NL.equals(chunk.substring(chunk.length() - 1))) {
                contextBuilder.append(NL);
            }
        }

        return contextBuilder.toString();
    }

    private List<Context> getSearchResults(ChatCompletionRequest request)
    {
        if (request.messages().isEmpty()) {
            return Collections.emptyList();
        }
    
        ChatMessage lastMessage = request.messages().get(request.messages().size() - 1);
        String message = lastMessage.getContent();

        // Perform solr similarity search on the last message
        try {
            return this.collectionManager.hybridSearch(message, this.collections, this.maxSemanticResults,
                this.maxKeywordResults);
        } catch (Exception e) {
            this.logger.error(ERROR_LOG_FORMAT, SIMILARITY_SEARCH_ERROR_MSG, ExceptionUtils.getRootCauseMessage(e));
            return Collections.emptyList();
        }
    }

    private String extractURLsAndformat(List<Context> searchResults)
    {
        StringBuilder sourcesBuilder = new StringBuilder();
        List<String> addedUrls = new ArrayList<>();

        for (Context result : searchResults) {
            String sourceURL = result.url();

            // Check if URL has already been added
            if (!addedUrls.contains(sourceURL)) {
                sourcesBuilder.append(String.format(SOURCE_STRING, sourceURL));
                addedUrls.add(sourceURL);
            }
        }

        return sourcesBuilder.toString();
    }
}
