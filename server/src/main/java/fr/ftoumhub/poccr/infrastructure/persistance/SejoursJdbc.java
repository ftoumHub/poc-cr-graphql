package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.port.Sejours;
import fr.ftoumhub.poccr.domaine.sejour.Sejour;
import fr.ftoumhub.poccr.domaine.sejour.TypeDocument;
import fr.ftoumhub.poccr.domaine.sejour.TypeSejour;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class SejoursJdbc implements Sejours {

    private static final RowMapper<Sejour> LIGNE_VERS_SEJOUR = (rs, i) -> {
        Date sortie = rs.getDate("date_sortie");
        String typeDocument = rs.getString("type_document");
        return Sejour.builder()
                .id(rs.getObject("id", UUID.class))
                .patientId(rs.getObject("patient_id", UUID.class))
                .numero(rs.getString("numero"))
                .type(TypeSejour.valueOf(rs.getString("type")))
                .dateEntree(rs.getDate("date_entree").toLocalDate())
                .dateSortie(sortie == null ? null : sortie.toLocalDate())
                .service(rs.getString("service"))
                .motif(rs.getString("motif"))
                .medecinResponsable(rs.getString("medecin_responsable"))
                .typeDocument(typeDocument == null ? null : TypeDocument.valueOf(typeDocument))
                .diagnosticCim10Code(rs.getString("diagnostic_cim10_code"))
                .diagnosticCim10Libelle(rs.getString("diagnostic_cim10_libelle"))
                .acteCcamCode(rs.getString("acte_ccam_code"))
                .acteCcamLibelle(rs.getString("acte_ccam_libelle"))
                .construire();
    };

    private final JdbcClient jdbc;

    public SejoursJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, List<Sejour>> dePatients(Set<UUID> patientIds) {
        if (patientIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("""
                        select id, patient_id, numero, type, date_entree, date_sortie, service, motif, medecin_responsable,
                               type_document, diagnostic_cim10_code, diagnostic_cim10_libelle, acte_ccam_code, acte_ccam_libelle
                        from sejour
                        where patient_id in (:ids)
                        order by date_entree desc
                        """)
                .param("ids", List.copyOf(patientIds))
                .query(LIGNE_VERS_SEJOUR)
                .list()
                .stream()
                .collect(Collectors.groupingBy(Sejour::patientId));
    }
}
