package fr.ftoumhub.poccr.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.ftoumhub.poccr.application.ConsultationDocuments;
import fr.ftoumhub.poccr.application.ConsultationDossierPatient;
import fr.ftoumhub.poccr.application.GestionDocuments;
import fr.ftoumhub.poccr.application.ValidationCompteRendu;
import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.port.FichiersDocuments;
import fr.ftoumhub.poccr.infrastructure.editeur.ArchiveValidationsFichiers;
import fr.ftoumhub.poccr.infrastructure.editeur.ControleurActeurs;
import fr.ftoumhub.poccr.infrastructure.editeur.ControleurDocuments;
import fr.ftoumhub.poccr.infrastructure.editeur.FichiersDocumentsDisque;
import fr.ftoumhub.poccr.infrastructure.editeur.ProprietesEditeur;
import fr.ftoumhub.poccr.infrastructure.editeur.StockageDocuments;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProprietesEditeur.class)
public class ConfigurationEditeur {

    /**
     * Le manifeste du plugin (pluginsData) est lu par du JS qui tourne dans l'origine du
     * Document Server (éditeur en iframe sur url-document-server), pas dans celle de ce serveur :
     * c'est un appel cross-origin. Sans ces en-têtes, le fetch échoue silencieusement côté
     * éditeur et l'onglet du plugin n'apparaît jamais.
     */
    @Bean
    WebMvcConfigurer corsPourPlugin(ProprietesEditeur proprietes) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registre) {
                registre.addMapping("/plugin/**").allowedOrigins(proprietes.urlDocumentServer()).allowedMethods("GET");
            }
        };
    }

    @Bean
    StockageDocuments stockageDocuments(ProprietesEditeur proprietes) {
        return new StockageDocuments(proprietes.dossierDocuments());
    }

    @Bean
    FichiersDocuments fichiersDocuments(StockageDocuments stockage) {
        return new FichiersDocumentsDisque(stockage);
    }

    /** Documents antérieurs aux fiches (simples .docx) : une fiche est créée au démarrage pour chacun. */
    @Bean
    ApplicationRunner recensementDocuments(GestionDocuments gestion) {
        return arguments -> {
            int crees = gestion.recenser();
            if (crees > 0) {
                LoggerFactory.getLogger(GestionDocuments.class).info("{} document(s) existant(s) recensé(s)", crees);
            }
        };
    }

    @Bean
    ArchiveValidations archiveValidations(ProprietesEditeur proprietes, ObjectMapper objectMapper) {
        return new ArchiveValidationsFichiers(proprietes.dossierDocuments(), objectMapper);
    }

    @Bean
    ControleurDocuments controleurDocuments(StockageDocuments stockage, GestionDocuments gestion,
                                            ValidationCompteRendu validation, ConsultationDossierPatient consultation,
                                            ConsultationDocuments consultationDocuments, ProprietesEditeur proprietes) {
        return new ControleurDocuments(stockage, gestion, validation, consultation, consultationDocuments, proprietes);
    }

    @Bean
    ControleurActeurs controleurActeurs(GestionDocuments gestion, ConsultationDocuments consultationDocuments,
                                        ConsultationDossierPatient consultation, ValidationCompteRendu validation) {
        return new ControleurActeurs(gestion, consultationDocuments, consultation, validation);
    }
}
