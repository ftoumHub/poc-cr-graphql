package fr.ftoumhub.poccr.configuration;

import fr.ftoumhub.poccr.application.ConsultationDocuments;
import fr.ftoumhub.poccr.application.ConsultationDossierPatient;
import fr.ftoumhub.poccr.application.GestionDocuments;
import fr.ftoumhub.poccr.application.ValidationCompteRendu;
import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.port.Documents;
import fr.ftoumhub.poccr.domaine.port.Etablissements;
import fr.ftoumhub.poccr.domaine.port.ExecuteurRequetesDossier;
import fr.ftoumhub.poccr.domaine.port.FichiersDocuments;
import fr.ftoumhub.poccr.domaine.port.Observations;
import fr.ftoumhub.poccr.domaine.port.Patients;
import fr.ftoumhub.poccr.domaine.port.ProfessionnelsSante;
import fr.ftoumhub.poccr.domaine.port.Sejours;
import fr.ftoumhub.poccr.domaine.port.Traitements;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
public class ConfigurationDomaine {

    @Bean
    @ConditionalOnMissingBean
    Clock horloge() {
        return Clock.system(ZoneId.of("Europe/Paris"));
    }

    @Bean
    ConsultationDossierPatient consultationDossierPatient(Patients patients, Observations observations,
                                                          Traitements traitements, Sejours sejours, Clock horloge) {
        return new ConsultationDossierPatient(patients, observations, traitements, sejours, horloge);
    }

    @Bean
    ValidationCompteRendu validationCompteRendu(ExecuteurRequetesDossier executeur, ArchiveValidations archive,
                                                Documents documents, ProfessionnelsSante professionnels,
                                                Clock horloge) {
        return new ValidationCompteRendu(executeur, archive, documents, professionnels, horloge);
    }

    @Bean
    GestionDocuments gestionDocuments(Documents documents, FichiersDocuments fichiers, ArchiveValidations archive,
                                      Etablissements etablissements, ProfessionnelsSante professionnels,
                                      Clock horloge) {
        return new GestionDocuments(documents, fichiers, archive, etablissements, professionnels, horloge,
                UUID::randomUUID);
    }

    @Bean
    ConsultationDocuments consultationDocuments(Documents documents, ProfessionnelsSante professionnels,
                                                Etablissements etablissements, ArchiveValidations archive) {
        return new ConsultationDocuments(documents, professionnels, etablissements, archive);
    }
}
