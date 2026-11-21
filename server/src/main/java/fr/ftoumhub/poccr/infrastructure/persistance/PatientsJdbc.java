package fr.ftoumhub.poccr.infrastructure.persistance;

import fr.ftoumhub.poccr.domaine.ResultatPage;
import fr.ftoumhub.poccr.domaine.patient.FiltrePatients;
import fr.ftoumhub.poccr.domaine.patient.Ipp;
import fr.ftoumhub.poccr.domaine.patient.Patient;
import fr.ftoumhub.poccr.domaine.patient.Sexe;
import fr.ftoumhub.poccr.domaine.port.Patients;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class PatientsJdbc implements Patients {

    private static final String COLONNES = "id, ipp, nom, prenom, date_naissance, sexe, nom_naissance, prenoms_naissance, "
            + "lieu_naissance, date_naissance_amo, nir, email, nationalite, medecin_traitant_id";

    private static final RowMapper<Patient> LIGNE_VERS_PATIENT = (rs, i) -> Patient.builder()
            .id(rs.getObject("id", UUID.class))
            .ipp(new Ipp(rs.getString("ipp")))
            .nom(rs.getString("nom"))
            .prenom(rs.getString("prenom"))
            .dateNaissance(rs.getDate("date_naissance").toLocalDate())
            .sexe(Sexe.valueOf(rs.getString("sexe")))
            .nomNaissance(rs.getString("nom_naissance"))
            .prenomsNaissance(rs.getString("prenoms_naissance"))
            .lieuNaissance(rs.getString("lieu_naissance"))
            .dateNaissanceAmo(rs.getString("date_naissance_amo"))
            .nir(rs.getString("nir"))
            .email(rs.getString("email"))
            .nationalite(rs.getString("nationalite"))
            .medecinTraitantId(rs.getObject("medecin_traitant_id", UUID.class))
            .construire();

    private final JdbcClient jdbc;

    public PatientsJdbc(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Patient> parId(UUID id) {
        return jdbc.sql("select " + COLONNES + " from patient where id = :id")
                .param("id", id)
                .query(LIGNE_VERS_PATIENT)
                .optional();
    }

    @Override
    public List<Patient> rechercher(String texte, int limite) {
        return jdbc.sql("""
                        select %s from patient
                        where ipp = :texte
                           or lower(nom) like lower(:prefixe)
                           or lower(prenom) like lower(:prefixe)
                        order by nom, prenom
                        limit :limite
                        """.formatted(COLONNES))
                .param("texte", texte)
                .param("prefixe", texte + "%")
                .param("limite", limite)
                .query(LIGNE_VERS_PATIENT)
                .list();
    }

    @Override
    public ResultatPage<Patient> lister(FiltrePatients filtre, int page, int taille) {
        StringBuilder ou = new StringBuilder(" where 1 = 1");
        Map<String, Object> parametres = new HashMap<>();
        ajouterFiltreTexte(ou, parametres, "ipp", filtre.ipp());
        ajouterFiltreTexte(ou, parametres, "nom", filtre.nom());
        ajouterFiltreTexte(ou, parametres, "prenom", filtre.prenom());
        ajouterFiltreTexte(ou, parametres, "sexe", filtre.sexe());
        ajouterFiltreContexte(ou, parametres, filtre);
        if (filtre.dateNaissance() != null && !filtre.dateNaissance().isBlank()) {
            ou.append(" and to_char(date_naissance, 'YYYY-MM-DD') like :dateNaissance");
            parametres.put("dateNaissance", "%" + filtre.dateNaissance() + "%");
        }

        long total = jdbc.sql("select count(*) from patient" + ou)
                .params(parametres)
                .query(Long.class)
                .single();
        List<Patient> elements = jdbc.sql(("select %s from patient" + ou + " order by nom, prenom limit :taille offset :decalage")
                        .formatted(COLONNES))
                .params(parametres)
                .param("taille", taille)
                .param("decalage", page * taille)
                .query(LIGNE_VERS_PATIENT)
                .list();
        return new ResultatPage<>(elements, total);
    }

    /** Patients ayant au moins un séjour dans l'UF, le service ou l'établissement du contexte. */
    private static void ajouterFiltreContexte(StringBuilder ou, Map<String, Object> parametres, FiltrePatients filtre) {
        String sejours = " and exists (select 1 from sejour s join unite_fonctionnelle u on u.code = s.uf_medicale_code"
                + " join service sv on sv.code = u.service_code where s.patient_id = patient.id and ";
        if (renseigne(filtre.uf())) {
            ou.append(sejours).append("u.code = :uf)");
            parametres.put("uf", filtre.uf());
        } else if (renseigne(filtre.service())) {
            ou.append(sejours).append("sv.code = :service)");
            parametres.put("service", filtre.service());
        } else if (renseigne(filtre.etablissement())) {
            ou.append(sejours).append("sv.etablissement_id = cast(:etablissement as uuid))");
            parametres.put("etablissement", filtre.etablissement());
        }
    }

    private static boolean renseigne(String valeur) {
        return valeur != null && !valeur.isBlank();
    }

    private static void ajouterFiltreTexte(StringBuilder ou, Map<String, Object> parametres, String colonne, String valeur) {
        if (valeur == null || valeur.isBlank()) {
            return;
        }
        ou.append(" and lower(").append(colonne).append(") like lower(:").append(colonne).append(")");
        parametres.put(colonne, "%" + valeur + "%");
    }
}
