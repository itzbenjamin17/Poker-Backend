package com.pokergame.wal;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

@Aspect
@Component
public class WalAspect {

    private static final Logger logger = LoggerFactory.getLogger(WalAspect.class);

    private final WalFileService walFileService;
    private final JsonMapper jsonMapper;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ParameterNameDiscoverer nameDiscoverer = new DefaultParameterNameDiscoverer();

    public WalAspect(WalFileService walFileService, JsonMapper jsonMapper) {
        this.walFileService = walFileService;
        this.jsonMapper = jsonMapper;
    }

    @Around("@annotation(walLogged)")
    public Object logToWal(ProceedingJoinPoint joinPoint, WalLogged walLogged) throws Throwable {
        if (WalContext.isReplaying()) {
            return joinPoint.proceed();
        }

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String serviceName = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = method.getName();
        Object[] args = joinPoint.getArgs();
        String[] paramNames = nameDiscoverer.getParameterNames(method);

        List<String> paramTypes = new ArrayList<>();
        List<JsonNode> jsonArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            paramTypes.add(method.getParameterTypes()[i].getName());
            jsonArgs.add(jsonMapper.valueToTree(args[i]));
        }

        Object result = joinPoint.proceed();

        String returnedId = null;
        if (result instanceof String) {
            returnedId = (String) result;
        }

        WalEvent event = new WalEvent(serviceName, methodName, paramTypes, jsonArgs, returnedId);

        String roomId = extractRoomId(walLogged.roomId(), paramNames, args, result);
        walFileService.appendEvent(roomId, event);
        
        return result;
    }

    private String extractRoomId(String expression, String[] paramNames, Object[] args, Object result) {
        EvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        if (result != null) {
            context.setVariable("result", result);
        }
        return parser.parseExpression(expression).getValue(context, String.class);
    }
}
