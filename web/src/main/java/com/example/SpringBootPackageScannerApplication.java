package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Web application entry point. The headless CLI / CI mode lives in the {@code core} module
 * ({@code com.example.softwaremetrics.cli.CliMain}), so this just starts the web UI.
 */
@SpringBootApplication(scanBasePackages = "com.example")
public class SpringBootPackageScannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringBootPackageScannerApplication.class, args);
    }
}
