package com.example.softwaremetrics.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@SpringBootTest
@AutoConfigureMockMvc
public class PackageScannerControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        TestProjectFixtures.createTestProjectStructure(tempDir);
    }

    @Test
    public void testIndexPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"));
    }

    @Test
    public void testScanSuccess() throws Exception {
        mockMvc.perform(post("/scan").param("path", tempDir.toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("graph :: graph"))
                .andExpect(model().attributeExists("metrics"))
                .andExpect(model().attribute("metrics", org.hamcrest.Matchers.hasKey("com.example.subpackage")));
    }

    @Test
    public void testScanWithArchitecture() throws Exception {
        mockMvc.perform(post("/scan").param("path", tempDir.toString()).param("arch", "layered"))
                .andExpect(status().isOk())
                .andExpect(view().name("graph :: graph"))
                .andExpect(model().attributeExists("architecture"));
    }

    @Test
    public void testExportMetricsWithArchitecture() throws Exception {
        mockMvc.perform(get("/api/metrics").param("path", tempDir.toString()).param("arch", "layered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.architecture").exists())
                .andExpect(jsonPath("$.architecture.specName", notNullValue()));
    }

    @Test
    public void testScanError() throws Exception {
        mockMvc.perform(post("/scan").param("path", "/non/existent/path"))
                .andExpect(status().isOk())
                .andExpect(view().name("graph :: error"))
                .andExpect(model().attributeExists("error"))
                .andExpect(model().attribute("error", org.hamcrest.Matchers.containsString("Error scanning project")));
    }

    @Test
    public void testExportMetricsJson() throws Exception {
        mockMvc.perform(get("/api/metrics").param("path", tempDir.toString()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.generatedAt", notNullValue()))
                .andExpect(jsonPath("$.projectPath", containsString(tempDir.toString())))
                .andExpect(jsonPath("$.packageCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.summary").exists())
                .andExpect(jsonPath("$.cycles", org.hamcrest.Matchers.empty()))
                .andExpect(jsonPath("$.packages", org.hamcrest.Matchers.hasKey("com.example.subpackage")));
    }

    @Test
    public void testExportMetricsError() throws Exception {
        mockMvc.perform(get("/api/metrics").param("path", "/non/existent/path"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", notNullValue()));
    }

    @Test
    void scanReportsCyclicDependencies(@TempDir Path cyclicDir) throws Exception {
        TestProjectFixtures.createCyclicProjectStructure(cyclicDir);

        mockMvc.perform(get("/api/metrics").param("path", cyclicDir.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycles").isArray())
                .andExpect(jsonPath("$.cycles.length()", equalTo(1)))
                .andExpect(jsonPath("$.cycles[0].length()", equalTo(2)))
                .andExpect(jsonPath("$.cycles[0]",
                        containsInAnyOrder("com.example.domain", "com.example.service")));

        mockMvc.perform(post("/scan").param("path", cyclicDir.toString()))
                .andExpect(status().isOk())
                .andExpect(view().name("graph :: graph"))
                .andExpect(model().attribute("cycles", hasSize(equalTo(1))))
                .andExpect(content().string(containsString("Circular dependencies detected")));
    }
}