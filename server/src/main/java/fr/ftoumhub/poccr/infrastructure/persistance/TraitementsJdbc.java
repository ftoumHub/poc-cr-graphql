package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.port.Traitements;
import fr.ftoumhub.poccr.domaine.traitement.Traitement;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class TraitementsJdbc implements Traitements {

    private static final RowMapper<Traitement> LIGNE_VERS_TRAITEMENT = (rs, i) -> {
        Date fin = rs.getDate("date_fin");
        return Traitement.builder()
                .id(rs.getObject("id", UUID.class))
                .patientId(rs.getObject("patient_id", UUID.class))
                .molecule(rs.getString("molecule"))
                .posologie(rs.getString("posologie"))
                .dateDebut(rs.getDate("date_debut").toLocalDate())
                .dateFin(fin == null ? null : fin.toLocalDate())
                .construire();
    };

    private final JdbcClient jdbc;

    public TraitementsJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, List<Traitement>> dePatients(Set<UUID> patientIds) {
        if (patientIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        select id, patient_id, molecule, posologie, date_debut, date_fin
                        from traitement
                        where patient_id in (:ids)
                        order by date_debut desc, molecule
                        """)
                .param("ids", List.copyOf(patientIds))
                .query(LIGNE_VERS_TRAITEMENT)
                .list()
                .stream()
                .collect(Collectors.groupingBy(Traitement::patientId));
    }
}
