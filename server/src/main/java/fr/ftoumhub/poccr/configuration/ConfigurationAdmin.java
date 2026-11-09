package fr.ftoumhub.poccr.configuration;

import fr.ftoumhub.poccr.infrastructure.admin.ControleurDonneesTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Outil de test (POC) : écriture directe sur le jeu de données, hors du modèle de lecture du domaine. */
@Configuration(proxyBeanMethods = false)
public class ConfigurationAdmin {

    @Bean
    ControleurDonneesTest controleurDonneesTest(JdbcClient jdbc) {
        return new ControleurDonneesTest(jdbc);
    }
}
