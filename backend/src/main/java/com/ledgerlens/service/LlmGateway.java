package com.ledgerlens.service;

import com.ledgerlens.entity.AuditLog;
import com.ledgerlens.repository.AuditLogRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The only place the model is called from.
 *
 * <p>Every call is written to the append-only audit log with the hash of the prompt, the model that
 * answered, how long it took and what came back, so an answer can always be traced to the exact
 * prompt that produced it.
 *
 * <p>The model is optional. Without a key the application still starts and reconciliation, the
 * waterfall and the metrics all work unchanged; only the three model-backed features refuse, and
 * they refuse with a message that says what is missing rather than failing somewhere upstream.
 */
@Service
public class LlmGateway {

    private static final int LOGGED_OUTPUT_LIMIT = 2000;

    /** Matches the placeholder in application.yml, so an unset environment variable is recognisable. */
    private static final String UNSET_KEY = "unset";

    private final ObjectProvider<ChatModel> chatModels;
    private final AuditLogRepository auditLogRepository;
    private final String modelName;
    private final boolean keyConfigured;

    public LlmGateway(ObjectProvider<ChatModel> chatModels,
                      AuditLogRepository auditLogRepository,
                      @Value("${spring.ai.google.genai.chat.options.model:unconfigured}") String modelName,
                      @Value("${spring.ai.google.genai.api-key:unset}") String apiKey) {
        this.chatModels = chatModels;
        this.auditLogRepository = auditLogRepository;
        this.modelName = modelName;
        this.keyConfigured = !apiKey.isBlank() && !UNSET_KEY.equals(apiKey);
    }

    public boolean available() {
        return keyConfigured && chatModels.getIfAvailable() != null;
    }

    public String complete(UUID batchId, String action, String prompt) {
        ChatModel model = chatModels.getIfAvailable();
        if (model == null || !keyConfigured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "no chat model is configured; set GEMINI_API_KEY to enable this");
        }
        long startedAt = System.nanoTime();
        String output;
        try {
            output = model.call(new Prompt(prompt)).getResult().getOutput().getText();
        } catch (RuntimeException e) {
            // An upstream failure is not a fault in this service, and a bare 500 tells nobody anything.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "the model could not be reached: " + rootMessage(e), e);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        record(batchId, action, prompt, output, elapsed);
        return output == null ? "" : output;
    }

    /** Appends the schema the model must answer in, then parses the reply back into that record. */
    public <T> T completeAs(UUID batchId, String action, String prompt, Class<T> type) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(type);
        return converter.convert(complete(batchId, action, prompt + "\n\n" + converter.getFormat()));
    }

    public static String loadPrompt(String name) {
        try (var stream = new ClassPathResource("prompts/" + name).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing prompt template prompts/" + name, e);
        }
    }

    private void record(UUID batchId, String action, String prompt, String output, Duration elapsed) {
        AuditLog entry = new AuditLog();
        entry.setLoggedAt(LocalDateTime.now());
        entry.setBatchId(batchId);
        entry.setAction(action);
        entry.setDetail("model=%s promptSha256=%s latencyMs=%d output=%s"
                .formatted(modelName, sha256(prompt), elapsed.toMillis(), truncate(output)));
        auditLogRepository.save(entry);
    }

    /** The useful sentence is usually at the bottom of the cause chain, not the top. */
    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static String truncate(String output) {
        if (output == null) {
            return "";
        }
        return output.length() <= LOGGED_OUTPUT_LIMIT ? output : output.substring(0, LOGGED_OUTPUT_LIMIT) + "...";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to log prompts", e);
        }
    }
}
