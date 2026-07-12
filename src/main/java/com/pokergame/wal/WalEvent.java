package com.pokergame.wal;

import tools.jackson.databind.JsonNode;
import java.util.List;

public record WalEvent(
        String serviceName,
        String methodName,
        List<String> parameterTypes,
        List<JsonNode> arguments,
        String returnedId
) {}
