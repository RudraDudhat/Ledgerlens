package com.ledgerlens;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Stands in for the real model so the suite runs with no API key and no network.
 *
 * <p>It also records every prompt it was handed, which is what lets the tests assert that the
 * narrator is only ever given numbers that were computed beforehand.
 */
public class StubChatModel implements ChatModel {

    private final List<String> prompts = new ArrayList<>();
    private Function<String, String> responder = prompt -> "stubbed";

    public void respondWith(String reply) {
        this.responder = prompt -> reply;
    }

    public void respondWith(Function<String, String> reply) {
        this.responder = reply;
    }

    public List<String> prompts() {
        return prompts;
    }

    public String lastPrompt() {
        return prompts.get(prompts.size() - 1);
    }

    public void reset() {
        prompts.clear();
        responder = prompt -> "stubbed";
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String text = prompt.getContents();
        prompts.add(text);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(responder.apply(text)))));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }
}
