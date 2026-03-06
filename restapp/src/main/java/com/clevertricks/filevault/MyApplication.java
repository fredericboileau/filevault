package com.clevertricks.filevault;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(StorageProperties.class)
public class MyApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }

    @Bean
    CommandLineRunner init(StorageService storageService, StorageProperties properties) {
        return (args) -> {
            storageService.init();
            storageService.deleteAll();
            System.out.println("Storage type: " + storageService.getClass().getSimpleName());
        };
    }
}
