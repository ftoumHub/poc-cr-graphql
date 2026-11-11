package fr.ftoumhub.poccr.configuration;

import fr.ftoumhub.poccr.domaine.port.Documents;
import fr.ftoumhub.poccr.domaine.port.Etablissements;
import fr.ftoumhub.poccr.domaine.port.Observations;
import fr.ftoumhub.poccr.domaine.port.Patients;
import fr.ftoumhub.poccr.domaine.port.ProfessionnelsSante;
import fr.ftoumhub.poccr.domaine.port.Sejours;
import fr.ftoumhub.poccr.domaine.port.Traitements;
import fr.ftoumhub.poccr.infrastructure.persistance.DocumentsJdbc;
import fr.ftoumhub.poccr.infrastructure.persistance.EtablissementsJdbc;
import fr.ftoumhub.poccr.infrastructure.persistance.ObservationsJdbc;
import fr.ftoumhub.poccr.infrastructure.persistance.PatientsJdbc;
import fr.ftoumhub.poccr.infrastructure.persistance.ProfessionnelsSanteJdbc;
import fr.ftoumhub.poccr.infrastructure.persistance.SejoursJdbc;
import fr.ftoumhub.poccr.infrastructure.persistance.TraitementsJdbc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class ConfigurationPersistance {

    @Bean
    Patients patients(JdbcClient jdbc) {
        return new PatientsJdbc(jdbc);
    }

    @Bean
    Observations observations(JdbcClient jdbc) {
        return new ObservationsJdbc(jdbc);
    }

    @Bean
    Traitements traitements(JdbcClient jdbc) {
        return new TraitementsJdbc(jdbc);
    }

    @Bean
    Sejours sejours(JdbcClient jdbc) {
        return new SejoursJdbc(jdbc);
    }

    @Bean
    Documents documents(JdbcClient jdbc, PlatformTransactionManager transactions) {
        return new DocumentsJdbc(jdbc, new TransactionTemplate(transactions));
    }

    @Bean
    ProfessionnelsSante professionnelsSante(JdbcClient jdbc) {
        return new ProfessionnelsSanteJdbc(jdbc);
    }

    @Bean
    Etablissements etablissements(JdbcClient jdbc) {
        return new EtablissementsJdbc(jdbc);
    }
}
