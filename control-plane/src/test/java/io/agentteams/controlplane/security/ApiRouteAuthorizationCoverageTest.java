package io.agentteams.controlplane.security;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** Fails CI when a new public controller route lacks a policy or service authorization entry. */
class ApiRouteAuthorizationCoverageTest {
    private static final Pattern CLASS_ROUTE = Pattern.compile("(?m)^\\s*@RequestMapping\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern METHOD_ROUTE = Pattern.compile(
            "(?m)^\\s*@(GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\\s*(?:\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"[^)]*\\))?");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");
    private static final String TEST_UUID = "4c2f4e83-3b54-44c2-ae55-2462e7a8b2e5";

    @Test
    void everyPublicControllerMappingHasAnExplicitAuthorizationBoundary() throws Exception {
        int[] routeCount = {0};
        try (var sources = Files.walk(Path.of("src/main/java"))) {
            for (Path source : sources.filter(path -> path.toString().endsWith("Controller.java")).toList()) {
                String content = Files.readString(source);
                String base = "";
                Matcher classRoute = CLASS_ROUTE.matcher(content);
                if (classRoute.find()) base = classRoute.group(1);
                Matcher mapping = METHOD_ROUTE.matcher(content);
                while (mapping.find()) {
                    String endpoint = mapping.group(2) == null ? "" : mapping.group(2);
                    if (endpoint.startsWith("/api/")) base = "";
                    String path = (base + endpoint).replaceAll("//+", "/");
                    if (!path.startsWith("/api/")) continue;
                    final String routePath = samplePath(path);
                    routeCount[0]++;
                    String method = switch (mapping.group(1)) {
                        case "GetMapping" -> "GET";
                        case "PostMapping" -> "POST";
                        case "PutMapping" -> "PUT";
                        case "PatchMapping" -> "PATCH";
                        case "DeleteMapping" -> "DELETE";
                        default -> throw new IllegalStateException("unsupported mapping annotation");
                    };
                    MockHttpServletRequest request = new MockHttpServletRequest(method, routePath);
                    request.setRequestURI(routePath);
                    assertTrue(ApiAuthorizationPolicy.hasExplicitAuthorizationBoundary(request),
                            () -> source + " has unclassified route " + method + " " + routePath);
                }
            }
        }
        assertTrue(routeCount[0] > 50, "controller route inventory parser should cover public routes");
    }

    private static String samplePath(String path) {
        Matcher variables = PATH_VARIABLE.matcher(path);
        StringBuffer resolved = new StringBuffer();
        while (variables.find()) {
            String name = variables.group(1).toLowerCase();
            String value = name.contains("project") || name.equals("id") || name.endsWith("id")
                    ? TEST_UUID : "subject";
            variables.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        variables.appendTail(resolved);
        return resolved.toString();
    }
}
