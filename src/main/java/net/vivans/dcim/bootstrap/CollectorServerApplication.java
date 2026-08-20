package net.vivans.dcim.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
        scanBasePackages = "net.vivans.dcim",
        exclude = UserDetailsServiceAutoConfiguration.class
)
@EnableScheduling
public class CollectorServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollectorServerApplication.class, args);
    }
}
