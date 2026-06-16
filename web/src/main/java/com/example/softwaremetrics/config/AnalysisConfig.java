package com.example.softwaremetrics.config;

import com.example.softwaremetrics.application.AnalysisService;
import com.example.softwaremetrics.domain.InstabilityCalculatorProperties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Spring-free {@code core} engine into the web app. The only configurable piece is the
 * dependency-exclusion list ({@link InstabilityCalculatorProperties}), bound from
 * {@code instability-calculator} in {@code application.yaml} via {@code @Bean @ConfigurationProperties}
 * (so YAML can override the code defaults). The whole analysis object graph is then hand-wired by
 * {@link AnalysisService#create(InstabilityCalculatorProperties)} — the controller depends only on the
 * resulting {@link AnalysisService}.
 */
@Configuration
public class AnalysisConfig {

    @Bean
    @ConfigurationProperties(prefix = "instability-calculator")
    public InstabilityCalculatorProperties instabilityCalculatorProperties() {
        return Defaults.exclusions();
    }

    @Bean
    public AnalysisService analysisService(InstabilityCalculatorProperties props) {
        return AnalysisService.create(props);
    }
}
