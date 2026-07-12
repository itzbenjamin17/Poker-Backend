package com.pokergame.wal;

import tools.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Service
public class WalReplayService {

    private static final Logger logger = LoggerFactory.getLogger(WalReplayService.class);
    private final WalFileService walFileService;
    private final JsonMapper jsonMapper;
    private final ApplicationContext applicationContext;

    public WalReplayService(WalFileService walFileService, JsonMapper jsonMapper, ApplicationContext applicationContext) {
        this.walFileService = walFileService;
        this.jsonMapper = jsonMapper;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void replayAll() {
        try {
            WalContext.setGlobalReplayMode(true);
            logger.info("Starting WAL replay process...");
            try (Stream<Path> files = walFileService.getAllWalFiles()) {
                files.forEach(this::replayFile);
            }
            logger.info("WAL replay process completed.");
        } catch (IOException e) {
            logger.error("Failed to read WAL files for replay", e);
        } finally {
            WalContext.setGlobalReplayMode(false);
        }
    }

    private void replayFile(Path file) {
        logger.info("Replaying WAL file: {}", file);
        try (Stream<String> lines = Files.lines(file)) {
            lines.forEach(line -> {
                if (!line.trim().isEmpty()) {
                    try {
                        WalEvent event = jsonMapper.readValue(line, WalEvent.class);
                        replayEvent(event);
                    } catch (Exception e) {
                        logger.error("Failed to replay event from line: {}", line, e);
                    }
                }
            });
        } catch (IOException e) {
            logger.error("Error reading WAL file: {}", file, e);
        }
    }

    private void replayEvent(WalEvent event) throws Exception {
        Object service = getServiceBean(event.serviceName());
        if (service == null) {
            logger.error("Could not find service: {}", event.serviceName());
            return;
        }

        List<String> typeNames = event.parameterTypes();
        Class<?>[] paramTypes = new Class<?>[typeNames.size()];
        Object[] args = new Object[typeNames.size()];

        for (int i = 0; i < typeNames.size(); i++) {
            Class<?> clazz = resolveClass(typeNames.get(i));
            paramTypes[i] = clazz;
            args[i] = jsonMapper.treeToValue(event.arguments().get(i), clazz);
        }

        java.lang.reflect.Method method = service.getClass().getMethod(event.methodName(), paramTypes);
        
        if (event.returnedId() != null) {
            WalContext.setOverrideRoomId(event.returnedId());
        }
        
        try {
            method.invoke(service, args);
        } finally {
            WalContext.clear();
        }
    }

    private Object getServiceBean(String simpleName) {
        String beanName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        try {
            return applicationContext.getBean(beanName);
        } catch (Exception e) {
            return null;
        }
    }

    private Class<?> resolveClass(String className) throws ClassNotFoundException {
        return switch (className) {
            case "boolean" -> boolean.class;
            case "byte" -> byte.class;
            case "short" -> short.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "char" -> char.class;
            default -> Class.forName(className);
        };
    }
}
