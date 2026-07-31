package io.github.yourname.agentstudio;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@ConfigurationPropertiesScan
@EnableScheduling
@SpringBootApplication
public class AgentstudioApplication {

	public static void main(String[] args) {
		SpringApplication.run(AgentstudioApplication.class, args);
	}

}
