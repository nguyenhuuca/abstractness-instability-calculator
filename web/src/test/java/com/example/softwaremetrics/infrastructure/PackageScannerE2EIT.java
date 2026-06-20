package com.example.softwaremetrics.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack E2E integration tests that exercise the real HTTP server on a random port.
 * Unlike PackageScannerControllerIT (MockMvc), these tests go through the actual servlet
 * container and verify HTTP-level behaviour: status codes, Content-Type headers, and
 * rendered/serialised response bodies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PackageScannerE2EIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        TestProjectFixtures.createTestProjectStructure(tempDir);
    }

    // 1. GET / — index page contains all required form elements
    @Test
    void indexPageIsHtmlAndContainsForm() {
        ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
        assertThat(response.getBody())
                .contains("name=\"path\"")
                .contains("name=\"arch\"")
                .contains("type=\"submit\"")
                .contains("id=\"result\"");
    }

    // 2. POST /scan — valid path returns HTML fragment with chart and package data
    @Test
    void scanSuccessReturnsHtmlWithMetricsChart() {
        ResponseEntity<String> response = postScan(tempDir.toString(), "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
        assertThat(response.getBody())
                .contains("metricsChart")
                .contains("com.example.subpackage")
                .doesNotContain("Error scanning project");
    }

    // 3. POST /scan — non-existent path returns error fragment (still 200 per htmx contract)
    @Test
    void scanWithInvalidPathReturnsErrorFragment() {
        ResponseEntity<String> response = postScan("/non/existent/path", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Error scanning project");
    }

    // 4. POST /scan — with arch=layered renders architecture result block
    @Test
    void scanWithArchLayeredRendersArchitectureBlock() {
        ResponseEntity<String> response = postScan(tempDir.toString(), "layered");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Either an arch-pass banner or a violations banner must appear
        assertThat(response.getBody()).satisfiesAnyOf(
                b -> assertThat(b).contains("arch-pass"),
                b -> assertThat(b).contains("cycle-banner")
        );
    }

    // 5. GET /api/metrics — returns JSON with all required envelope fields
    @Test
    void apiMetricsReturnsCompleteJsonEnvelope() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/metrics?path={path}", String.class, tempDir.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .satisfies(ct -> assertThat(ct.isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue());
        assertThat(response.getBody())
                .contains("\"generatedAt\"")
                .contains("\"projectPath\"")
                .contains("\"packageCount\"")
                .contains("\"summary\"")
                .contains("\"packages\"")
                .contains("\"cycles\"")
                .contains("com.example.subpackage");
    }

    // 6. GET /api/metrics — bad path → 400 with error field
    @Test
    void apiMetricsWithInvalidPathReturns400AndErrorJson() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/metrics?path={path}", String.class, "/non/existent/path");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"error\"");
    }

    // 7. GET /api/metrics — with arch param → envelope includes "architecture" field
    @Test
    void apiMetricsWithArchParamIncludesArchitectureField() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/metrics?path={path}&arch=layered", String.class, tempDir.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"architecture\"");
    }

    // 8. GET /api/metrics — without arch → "architecture" field absent (NON_NULL serialisation)
    @Test
    void apiMetricsWithoutArchOmitsArchitectureField() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/metrics?path={path}", String.class, tempDir.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("\"architecture\"");
    }

    // 9. POST /scan on a cyclic project — HTML shows the cycle banner
    @Test
    void scanCyclicProjectRendersCycleBanner(@TempDir Path cyclicDir) throws IOException {
        TestProjectFixtures.createCyclicProjectStructure(cyclicDir);

        ResponseEntity<String> response = postScan(cyclicDir.toString(), "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Circular dependencies detected");
    }

    // 10. GET /api/metrics on a cyclic project — JSON cycles array lists both packages
    @Test
    void apiMetricsCyclicProjectReturnsCyclesInJson(@TempDir Path cyclicDir) throws IOException {
        TestProjectFixtures.createCyclicProjectStructure(cyclicDir);

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/metrics?path={path}", String.class, cyclicDir.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).contains("com.example.domain");
        assertThat(body).contains("com.example.service");
        // cycles is a non-empty array-of-arrays
        assertThat(body).containsPattern("\"cycles\"\\s*:\\s*\\[\\[");
    }

    // 11. Non-cyclic project JSON — cycles is an empty array, not absent
    @Test
    void apiMetricsNonCyclicProjectHasEmptyCyclesArray() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/metrics?path={path}", String.class, tempDir.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"cycles\":[]");
    }

    // 12. Error message must not expose Java exception class names
    @Test
    void scanErrorMessageDoesNotExposeExceptionClassName() {
        ResponseEntity<String> response = postScan("/non/existent/path", "");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("Error scanning project")
                .doesNotContain("java.nio.file")
                .doesNotContain("NoSuchFileException")
                .doesNotContain("IllegalStateException")
                .doesNotContain("IllegalArgumentException");
    }

    // ---- helpers ----

    private ResponseEntity<String> postScan(String path, String arch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("path", path);
        form.add("arch", arch);
        return restTemplate.postForEntity("/scan", new HttpEntity<>(form, headers), String.class);
    }
}
