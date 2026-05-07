package com.baguwen.bot.service;

public interface BaguwenAgentService {

    String chat(String userId, String message);

    boolean isMemoryNearFull(String userId);

    void clearMemory(String userId);
}
