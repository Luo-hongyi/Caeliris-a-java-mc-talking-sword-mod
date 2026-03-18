package dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LLMMessage {

    public enum Role {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant");

        private final String apiName;

        Role(String apiName) {
            this.apiName = apiName;
        }

        public String getApiName() {
            return apiName;
        }
    }

    public interface ContentPart {
    }

    public static final class TextPart implements ContentPart {
        private final String text;

        public TextPart(String text) {
            this.text = text == null ? "" : text;
        }

        public String getText() {
            return text;
        }
    }

    public static final class ImagePart implements ContentPart {
        private final String mediaType;
        private final String base64Data;

        public ImagePart(String mediaType, String base64Data) {
            this.mediaType = mediaType == null || mediaType.isBlank() ? "image/png" : mediaType;
            this.base64Data = base64Data == null ? "" : base64Data;
        }

        public String getMediaType() {
            return mediaType;
        }

        public String getBase64Data() {
            return base64Data;
        }

        public String toDataUri() {
            return "data:" + mediaType + ";base64," + base64Data;
        }
    }

    private final Role role;
    private final List<ContentPart> content;

    private LLMMessage(Role role, List<ContentPart> content) {
        this.role = role;
        this.content = Collections.unmodifiableList(new ArrayList<>(content));
    }

    public static LLMMessage system(String text) {
        return of(Role.SYSTEM, List.of(new TextPart(text)));
    }

    public static LLMMessage user(String text) {
        return of(Role.USER, List.of(new TextPart(text)));
    }

    public static LLMMessage assistant(String text) {
        return of(Role.ASSISTANT, List.of(new TextPart(text)));
    }

    public static LLMMessage userWithImage(String text, String base64Data, String mediaType) {
        List<ContentPart> parts = new ArrayList<>();
        parts.add(new TextPart(text));
        parts.add(new ImagePart(mediaType, base64Data));
        return of(Role.USER, parts);
    }

    public static LLMMessage of(Role role, List<ContentPart> content) {
        return new LLMMessage(role, content == null ? List.of() : content);
    }

    public Role getRole() {
        return role;
    }

    public List<ContentPart> getContent() {
        return content;
    }
}
