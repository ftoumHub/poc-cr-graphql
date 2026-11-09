package fr.ftoumhub.poccr.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ftoumhub.poccr.application.ConsultationDossierPatient;
import fr.ftoumhub.poccr.domaine.port.ExecuteurRequetesDossier;
import fr.ftoumhub.poccr.infrastructure.graphql.ControleurDossierPatient;
import fr.ftoumhub.poccr.infrastructure.graphql.ExecuteurGraphQl;
import fr.ftoumhub.poccr.infrastructure.graphql.GestionErreursGraphQl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.execution.BatchLoaderRegistry;

@Configuration(proxyBeanMethods = false)
public class ConfigurationGraphQl {

    /** Déclaré en @Bean : Spring GraphQL détecte l'annotation @Controller sur le type du bean. */
    @Bean
    ControleurDossierPatient controleurDossierPatient(ConsultationDossierPatient consultation, BatchLoaderRegistry registre) {
        return new ControleurDossierPatient(consultation, registre);
    }

    @Bean
    GestionErreursGraphQl gestionErreursGraphQl() {
        return new GestionErreursGraphQl();
    }

    @Bean
    ExecuteurRequetesDossier executeurRequetesDossier(ExecutionGraphQlService graphQl, ObjectMapper objectMapper) {
        return new ExecuteurGraphQl(graphQl, objectMapper);
    }
}
