package com.mercur.upgrade.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rejects unauthenticated (401) and unauthorized (403) API calls with the same RFC 9457 problem body as every
 * other API error. The standard bearer-token handlers set the status and the {@code WWW-Authenticate} header
 * (RFC 6750) first.
 */
class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
    private final BearerTokenAccessDeniedHandler accessDenied = new BearerTokenAccessDeniedHandler();
    private final JsonMapper jsonMapper;

    SecurityProblemHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        entryPoint.commence(request, response, e);
        String detail = e instanceof InvalidBearerTokenException
                ? "The access token is invalid or has expired. Log in again."
                : "Log in first: this request needs an access token (Authorization: Bearer <token>).";
        write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized", detail);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        accessDenied.handle(request, response, e);
        write(request, response, HttpStatus.FORBIDDEN, "Forbidden", "The access token does not allow this request.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String title,
                       String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", title);
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
