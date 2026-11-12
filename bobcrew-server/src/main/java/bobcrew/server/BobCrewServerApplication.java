package bobcrew.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BobCrewProperties.class)
public class BobCrewServerApplication {

    public static void main(String[] args) {
        if (args.length > 0 && IssueTokenCommand.NAME.equals(args[0])) {
            System.exit(IssueTokenCommand.run(args, System.out, System.err));
        }
        SpringApplication.run(BobCrewServerApplication.class, args);
    }
}
