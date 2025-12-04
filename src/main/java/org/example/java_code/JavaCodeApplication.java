package org.example.java_code;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class JavaCodeApplication {

	public static void main(String[] args) {
		SpringApplication.run(JavaCodeApplication.class, args);
	}
}
