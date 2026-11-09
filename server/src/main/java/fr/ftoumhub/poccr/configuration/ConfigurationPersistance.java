package fr.ftoumhub.poccr.configuration;

import fr.ftoumhub.poccr.domaine.port.Observations;
import fr.ftoumhub.poccr.domaine.port.Patients;
import fr.ftoumhub.poccr.domaine.port.Sejours;
import fr.ftoumhub.poccr.domaine.port.Traitements;
import fr.ftoumhub.poccr.infrastructure.persistance.ObservationsJdbc;
import fr.ftoumhub.poccr.infrastructure.persistance.PatientsJdbc;
import fr.ftoumhub.poccr.infrastructure.persistance.SejoursJdbc;
import fr.ftoumhub.poccr.infrastructure.persistance.TraitementsJdbc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

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
}
