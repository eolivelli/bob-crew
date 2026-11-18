package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

/** Keeps the upload limit's default in {@link BobCrewProperties} and in {@code application.yml} the same. */
class UploadLimitDefaultTest {

    private static final DataSize LIMIT = DataSize.ofMegabytes(200);

    @Test
    void thePropertiesDefaultIsTwoHundredMegabytes() {
        BobCrewProperties properties = new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("bobcrew", BobCrewProperties.class);
        assertThat(properties.maxUploadSize()).isEqualTo(LIMIT);
    }

    @Test
    void theApplicationYmlDefaultIsTheSame() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties.getProperty("bobcrew.max-upload-size")).isEqualTo("${BOBCREW_MAX_UPLOAD_SIZE:200MB}");
    }
}
