package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.observation.CategorieObservation;
import fr.ftoumhub.poccr.domaine.observation.FiltreObservations;
import fr.ftoumhub.poccr.domaine.observation.Observation;
import fr.ftoumhub.poccr.domaine.port.Observations;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ObservationsJdbc implements Observations {

    private static final RowMapper<Observation> LIGNE_VERS_OBSERVATION = (rs, i) -> Observation.builder()
            .id(rs.getObject("id", UUID.class))
            .patientId(rs.getObject("patient_id", UUID.class))
            .categorie(CategorieObservation.valueOf(rs.getString("categorie")))
            .code(rs.getString("code"))
            .libelle(rs.getString("libelle"))
            .valeur(rs.getString("valeur"))
            .unite(rs.getString("unite"))
            .dateObservation(rs.getDate("date_observation").toLocalDate())
            .construire();

    private final JdbcClient jdbc;

    public ObservationsJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, List<Observation>> dePatients(Set<UUID> patientIds, FiltreObservations filtre) {
        if (patientIds.isEmpty()) {
            return Map.of();
        }
        StringBuilder sql = new StringBuilder("""
                select id, patient_id, categorie, code, libelle, valeur, unite, date_observation
                from observation
                where patient_id in (:ids)
                """);
        Map<String, Object> parametres = new HashMap<>();
        parametres.put("ids", List.copyOf(patientIds));

        filtre.categorieAttendue().ifPresent(c -> {
            sql.append(" and categorie = :categorie");
            parametres.put("categorie", c.name());
        });
        filtre.codeAttendu().ifPresent(c -> {
            sql.append(" and code = :code");
            parametres.put("code", c);
        });
        filtre.depuisLe().ifPresent(d -> {
            sql.append(" and date_observation >= :depuis");
            parametres.put("depuis", Date.valueOf(d));
        });
        sql.append(" order by date_observation desc, libelle");

        return jdbc.sql(sql.toString())
                .params(parametres)
                .query(LIGNE_VERS_OBSERVATION)
                .list()
                .stream()
                .collect(Collectors.groupingBy(Observation::patientId));
    }
}
