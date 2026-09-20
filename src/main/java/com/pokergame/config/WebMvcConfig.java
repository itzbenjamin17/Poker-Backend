package com.pokergame.config;

import com.pokergame.exception.UnauthorisedActionException;
import com.pokergame.security.PlayerPrincipal;
import com.pokergame.util.SecurityUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.security.Principal;
import java.util.List;

/**
 * Spring MVC configuration registering custom method argument resolvers.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PlayerPrincipalArgumentResolver());
    }

    /**
     * Resolves controller parameters of type {@link PlayerPrincipal} annotated with
     * {@link AuthenticationPrincipal}.
     * <p>
     * Checks {@link SecurityContextHolder} first (standard Spring Security runtime path),
     * and falls back to {@link NativeWebRequest#getUserPrincipal()} (used in MockMvc tests).
     * </p>
     */
    public static class PlayerPrincipalArgumentResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
                    && PlayerPrincipal.class.isAssignableFrom(parameter.getParameterType());
        }

        @Override
        public Object resolveArgument(MethodParameter parameter,
                                      ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest,
                                      WebDataBinderFactory binderFactory) {
            // First check SecurityContextHolder (populated in runtime by JwtAuthenticationFilter)
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null) {
                PlayerPrincipal player = SecurityUtils.getPlayerOrNull(authentication);
                if (player != null) {
                    return player;
                }
            }

            // Fallback to request user principal (e.g. MockMvc .principal(auth))
            Principal userPrincipal = webRequest.getUserPrincipal();
            if (userPrincipal != null) {
                return SecurityUtils.getPlayer(userPrincipal);
            }

            throw new UnauthorisedActionException("Invalid authentication principal");
        }
    }
}
