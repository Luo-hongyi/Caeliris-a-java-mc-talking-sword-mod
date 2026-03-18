package dreamdust.talking_weapon.basic_talking_weapon.agent.llm_service;

import java.io.IOException;
import java.util.List;

public interface LLMChatClient {

    String generate(List<LLMMessage> messages) throws IOException, InterruptedException;
}
