package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.port.Sejours;
import fr.ftoumhub.poccr.domaine.sejour.Sejour;
import fr.ftoumhub.poccr.domaine.sejour.TypeDocument;
import fr.ftoumhub.poccr.domaine.sejour.TypeSejour;
import fr.ftoumhub.poccr.domaine.sejour.UniteFonctionnelle;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
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
                .ufMedicale(uf(rs, "ufm"))
                .ufHebergement(uf(rs, "ufh"))
                .medecinAdresseurId(rs.getObject("medecin_adresseur_id", UUID.class))
                .construire();
    };

    private static UniteFonctionnelle uf(ResultSet rs, String prefixe) throws SQLException {
        String code = rs.getString(prefixe + "_code");
        return code == null ? null : new UniteFonctionnelle(code, rs.getString(prefixe + "_libelle"),
                rs.getString(prefixe + "_telephone"), rs.getString(prefixe + "_fax"),
                rs.getString(prefixe + "_service_code"), rs.getString(prefixe + "_service"));
    }

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
                        select s.id, s.patient_id, s.numero, s.type, s.date_entree, s.date_sortie, s.service, s.motif,
                               s.medecin_responsable, s.type_document, s.diagnostic_cim10_code, s.diagnostic_cim10_libelle,
                               s.acte_ccam_code, s.acte_ccam_libelle, s.medecin_adresseur_id,
                               ufm.code as ufm_code, ufm.libelle as ufm_libelle, ufm.telephone as ufm_telephone, ufm.fax as ufm_fax,
                               ufm.service_code as ufm_service_code, sm.libelle as ufm_service,
                               ufh.code as ufh_code, ufh.libelle as ufh_libelle, ufh.telephone as ufh_telephone, ufh.fax as ufh_fax,
                               ufh.service_code as ufh_service_code, sh.libelle as ufh_service
                        from sejour s
                        left join unite_fonctionnelle ufm on ufm.code = s.uf_medicale_code
                        left join service sm on sm.code = ufm.service_code
                        left join unite_fonctionnelle ufh on ufh.code = s.uf_hebergement_code
                        left join service sh on sh.code = ufh.service_code
                        where s.patient_id in (:ids)
                        order by s.date_entree desc
                        """)
                .param("ids", List.copyOf(patientIds))
                .query(LIGNE_VERS_SEJOUR)
                .list()
                .stream()
                .collect(Collectors.groupingBy(Sejour::patientId));
    }
}
