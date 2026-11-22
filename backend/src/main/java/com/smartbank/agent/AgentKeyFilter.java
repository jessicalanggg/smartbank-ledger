package com.smartbank.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Signs in AI agents on the agent API ({@code /api/v1/agent/**}) by the header
 * {@code Authorization: Agent <key>}. The key must belong to a mandate that is
 * neither revoked nor expired. Everywhere else the header is ignored, so an agent key
 * can never reach the customer API.
 */
public class AgentKeyFilter extends OncePerRequestFilter {
    static final String SCHEME = "Agent ";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentKeyFilter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/agent/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            chain.doFilter(request, response);
            return;
        }
        var found = jdbc.query("""
                SELECT id, user_id, account_id FROM agent_mandates
                WHERE key_hash = ? AND revoked_at IS NULL AND expires_at > now()""",
                (rs, n) -> new AgentPrincipal(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("account_id", UUID.class)),
                AgentKeys.hash(header.substring(SCHEME.length()).trim()));
        if (found.isEmpty()) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                    "Unknown, revoked or expired agent key");
            problem.setTitle("Authentication failed");
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader("WWW-Authenticate", "Agent");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), problem);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new AgentAuthentication(found.get(0)));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }
}
