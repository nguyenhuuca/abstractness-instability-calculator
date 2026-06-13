package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.Arrays;

@SpringBootApplication(scanBasePackages = "com.example")
public class SpringBootPackageScannerApplication {

    public static void main(String[] args) {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(SpringBootPackageScannerApplication.class);
        // Headless CLI / CI mode: --scan=<path> runs a scan without starting the web server.
        if (isCliMode(args)) {
            // keep stdout clean for JSON output — quiet the logs in CLI mode. System properties
            // outrank application.yaml, so this actually overrides the dev DEBUG level there.
            System.setProperty("logging.level.root", "WARN");
            System.setProperty("logging.level.com.example", "WARN");
            builder.web(WebApplicationType.NONE)
                    .bannerMode(org.springframework.boot.Banner.Mode.OFF);
        }
        builder.run(args);
    }

    private static boolean isCliMode(String[] args) {
        return Arrays.stream(args).anyMatch(a -> a.equals("--scan") || a.startsWith("--scan="));
    }
}