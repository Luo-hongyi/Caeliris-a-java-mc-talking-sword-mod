package dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

final class OpenAiCompatibleChatClient implements LLMChatClient {

    private final HttpClient httpClient;
    private final String apiKey;
    private final String endpoint;
    private final String modelName;

    OpenAiCompatibleChatClient(String apiKey, String baseUrl, String modelName) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.endpoint = resolveEndpoint(baseUrl);
        this.modelName = modelName == null ? "" : modelName.trim();
    }

    @Override
    public String generate(List<LLMMessage> messages) throws IOException, InterruptedException {
        if (apiKey.isBlank()) {
            throw new IOException("API key is blank.");
        }
        if (modelName.isBlank()) {
            throw new IOException("Model name is blank.");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IOException("No LLM messages were provided.");
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("model", modelName);
        payload.add("messages", serializeMessages(messages));
        payload.addProperty("stream", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("LLM HTTP " + response.statusCode() + ": " + extractErrorMessage(body));
        }

        return extractAssistantText(body);
    }

    private JsonArray serializeMessages(List<LLMMessage> messages) {
        JsonArray array = new JsonArray();
        for (LLMMessage message : messages) {
            JsonObject object = new JsonObject();
            object.addProperty("role", message.getRole().getApiName());
            object.add("content", serializeContent(message));
            array.add(object);
        }
        return array;
    }

    private JsonElement serializeContent(LLMMessage message) {
        List<LLMMessage.ContentPart> parts = message.getContent();
        if (parts.size() == 1 && parts.get(0) instanceof LLMMessage.TextPart textPart) {
            return new JsonPrimitive(textPart.getText());
        }

        JsonArray array = new JsonArray();
        for (LLMMessage.ContentPart part : parts) {
            if (part instanceof LLMMessage.TextPart textPart) {
                JsonObject object = new JsonObject();
                object.addProperty("type", "text");
                object.addProperty("text", textPart.getText());
                array.add(object);
            } else if (part instanceof LLMMessage.ImagePart imagePart) {
                JsonObject object = new JsonObject();
                object.addProperty("type", "image_url");
                JsonObject image = new JsonObject();
                image.addProperty("url", imagePart.toDataUri());
                object.add("image_url", image);
                array.add(object);
            }
        }
        return array;
    }

    private String extractAssistantText(String body) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("LLM response did not contain choices: " + body);
            }

            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            if (message == null) {
                throw new IOException("LLM response did not contain message: " + body);
            }

            JsonElement content = message.get("content");
            if (content == null || content.isJsonNull()) {
                throw new IOException("LLM response did not contain content: " + body);
            }

            if (content.isJsonPrimitive()) {
                return content.getAsString();
            }

            if (content.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement element : content.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject part = element.getAsJsonObject();
                    JsonElement text = part.get("text");
                    if (text != null && !text.isJsonNull()) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(text.getAsString());
                    }
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("Failed to parse LLM response: " + e.getMessage(), e);
        }

        throw new IOException("LLM response content format is unsupported: " + body);
    }

    private String extractErrorMessage(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject error = root.getAsJsonObject("error");
            if (error != null) {
                JsonElement message = error.get("message");
                if (message != null && !message.isJsonNull()) {
                    return message.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return body.isBlank() ? "empty error body" : body;
    }

    private String resolveEndpoint(String baseUrl) {
        String normalized = baseUrl == null ? "" : baseUrl.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("Base URL is blank.");
        }
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/chat/completions")) {
            return normalized;
        }
        return normalized + "/chat/completions";
    }
}
