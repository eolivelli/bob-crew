package bobcrew.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(UiProperties.class)
public class BobCrewUiApplication {

    public static void main(String[] args) {
        SpringApplication.run(BobCrewUiApplication.class, args);
    }
}
