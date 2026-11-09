package fr.ftoumhub.poccr;

import fr.ftoumhub.poccr.configuration.ConfigurationAdmin;
import fr.ftoumhub.poccr.configuration.ConfigurationDomaine;
import fr.ftoumhub.poccr.configuration.ConfigurationEditeur;
import fr.ftoumhub.poccr.configuration.ConfigurationGraphQl;
import fr.ftoumhub.poccr.configuration.ConfigurationPersistance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/** Câblage explicite : pas de component scan. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({ConfigurationPersistance.class, ConfigurationDomaine.class, ConfigurationGraphQl.class,
        ConfigurationEditeur.class, ConfigurationAdmin.class})
public class PocCrApplication {

    public static void main(String[] args) {
        SpringApplication.run(PocCrApplication.class, args);
    }
}
