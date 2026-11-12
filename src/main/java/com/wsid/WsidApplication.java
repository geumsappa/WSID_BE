package com.wsid;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WsidApplication {

    public static void main(String[] args) {
        SpringApplication.run(WsidApplication.class, args);
    }
}
