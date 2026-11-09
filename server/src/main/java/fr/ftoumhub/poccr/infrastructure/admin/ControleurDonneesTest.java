package fr.ftoumhub.poccr.infrastructure.admin;

import fr.ftoumhub.poccr.domaine.observation.CategorieObservation;
import fr.ftoumhub.poccr.domaine.patient.Sexe;
import fr.ftoumhub.poccr.domaine.sejour.TypeDocument;
import fr.ftoumhub.poccr.domaine.sejour.TypeSejour;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Écriture directe sur le jeu de données fictif, pour tester le rafraîchissement du plugin
 * (modifier une valeur ici, puis « Rafraîchir les données » dans l'éditeur).
 *
 * Volontairement hors du modèle de lecture du domaine (ConsultationDossierPatient, ports
 * Observations/Traitements/Sejours sont en lecture seule) : ceci n'est pas une fonctionnalité
 * métier mais un outil de test du POC, câblé directement sur JdbcClient.
 */
@RestController
@RequestMapping("/api/admin")
public class ControleurDonneesTest {

    private final JdbcClient jdbc;

    public ControleurDonneesTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // --- Lecture brute -------------------------------------------------------------------------
    // Le schéma GraphQL (métier) n'expose pas les identifiants techniques des observations,
    // traitements et séjours : l'édition ligne à ligne en a besoin, d'où cette lecture dédiée.

    public record ObservationBrute(UUID id, String categorie, String code, String libelle, String valeur,
                                   String unite, String dateObservation) {
    }

    public record TraitementBrut(UUID id, String molecule, String posologie, String dateDebut, String dateFin) {
    }

    public record SejourBrut(UUID id, String numero, String type, String dateEntree, String dateSortie,
                             String service, String motif, String medecinResponsable,
                             String typeDocument, String diagnosticCim10Code, String diagnosticCim10Libelle,
                             String acteCcamCode, String acteCcamLibelle) {
    }

    public record DonneesBrutesPatient(List<ObservationBrute> observations, List<TraitementBrut> traitements,
                                       List<SejourBrut> sejours) {
    }

    @GetMapping("/patients/{id}/brut")
    public DonneesBrutesPatient brut(@PathVariable UUID id) {
        List<ObservationBrute> observations = jdbc.sql("""
                        select id, categorie, code, libelle, valeur, unite, date_observation
                        from observation where patient_id = :id order by date_observation desc
                        """)
                .param("id", id)
                .query((rs, i) -> new ObservationBrute(rs.getObject("id", UUID.class), rs.getString("categorie"),
                        rs.getString("code"), rs.getString("libelle"), rs.getString("valeur"), rs.getString("unite"),
                        rs.getDate("date_observation").toLocalDate().toString()))
                .list();
        List<TraitementBrut> traitements = jdbc.sql("""
                        select id, molecule, posologie, date_debut, date_fin
                        from traitement where patient_id = :id order by date_debut desc
                        """)
                .param("id", id)
                .query((rs, i) -> new TraitementBrut(rs.getObject("id", UUID.class), rs.getString("molecule"),
                        rs.getString("posologie"), rs.getDate("date_debut").toLocalDate().toString(),
                        rs.getDate("date_fin") == null ? null : rs.getDate("date_fin").toLocalDate().toString()))
                .list();
        List<SejourBrut> sejours = jdbc.sql("""
                        select id, numero, type, date_entree, date_sortie, service, motif, medecin_responsable,
                               type_document, diagnostic_cim10_code, diagnostic_cim10_libelle, acte_ccam_code, acte_ccam_libelle
                        from sejour where patient_id = :id order by date_entree desc
                        """)
                .param("id", id)
                .query((rs, i) -> new SejourBrut(rs.getObject("id", UUID.class), rs.getString("numero"),
                        rs.getString("type"), rs.getDate("date_entree").toLocalDate().toString(),
                        rs.getDate("date_sortie") == null ? null : rs.getDate("date_sortie").toLocalDate().toString(),
                        rs.getString("service"), rs.getString("motif"), rs.getString("medecin_responsable"),
                        rs.getString("type_document"), rs.getString("diagnostic_cim10_code"),
                        rs.getString("diagnostic_cim10_libelle"), rs.getString("acte_ccam_code"),
                        rs.getString("acte_ccam_libelle")))
                .list();
        return new DonneesBrutesPatient(observations, traitements, sejours);
    }

    // --- Patient -----------------------------------------------------------------------------

    public record PatientModifie(String nom, String prenom, String dateNaissance, String sexe) {
    }

    @PutMapping("/patients/{id}")
    public ResponseEntity<Void> modifierPatient(@PathVariable UUID id, @RequestBody PatientModifie p) {
        int n = jdbc.sql("""
                        update patient set nom = :nom, prenom = :prenom, date_naissance = :dateNaissance, sexe = :sexe
                        where id = :id
                        """)
                .param("nom", p.nom())
                .param("prenom", p.prenom())
                .param("dateNaissance", LocalDate.parse(p.dateNaissance()))
                .param("sexe", Sexe.valueOf(p.sexe()).name())
                .param("id", id)
                .update();
        return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    // --- Observations --------------------------------------------------------------------------

    public record ObservationEntree(String categorie, String code, String libelle, String valeur, String unite,
                                    String dateObservation) {
    }

    @PostMapping("/patients/{patientId}/observations")
    public ResponseEntity<Map<String, String>> creerObservation(@PathVariable UUID patientId,
                                                                 @RequestBody ObservationEntree o) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into observation (id, patient_id, categorie, code, libelle, valeur, unite, date_observation)
                        values (:id, :patientId, :categorie, :code, :libelle, :valeur, :unite, :dateObservation)
                        """)
                .param("id", id)
                .param("patientId", patientId)
                .param("categorie", CategorieObservation.valueOf(o.categorie()).name())
                .param("code", o.code())
                .param("libelle", o.libelle())
                .param("valeur", o.valeur())
                .param("unite", o.unite())
                .param("dateObservation", LocalDate.parse(o.dateObservation()))
                .update();
        return ResponseEntity.ok(Map.of("id", id.toString()));
    }

    @PutMapping("/observations/{id}")
    public ResponseEntity<Void> modifierObservation(@PathVariable UUID id, @RequestBody ObservationEntree o) {
        int n = jdbc.sql("""
                        update observation set categorie = :categorie, code = :code, libelle = :libelle,
                               valeur = :valeur, unite = :unite, date_observation = :dateObservation
                        where id = :id
                        """)
                .param("categorie", CategorieObservation.valueOf(o.categorie()).name())
                .param("code", o.code())
                .param("libelle", o.libelle())
                .param("valeur", o.valeur())
                .param("unite", o.unite())
                .param("dateObservation", LocalDate.parse(o.dateObservation()))
                .param("id", id)
                .update();
        return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    @DeleteMapping("/observations/{id}")
    public ResponseEntity<Void> supprimerObservation(@PathVariable UUID id) {
        int n = jdbc.sql("delete from observation where id = :id").param("id", id).update();
        return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    // --- Traitements -------------------------------------------------------------------------

    public record TraitementEntree(String molecule, String posologie, String dateDebut, String dateFin) {
    }

    @PostMapping("/patients/{patientId}/traitements")
    public ResponseEntity<Map<String, String>> creerTraitement(@PathVariable UUID patientId,
                                                                @RequestBody TraitementEntree t) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into traitement (id, patient_id, molecule, posologie, date_debut, date_fin)
                        values (:id, :patientId, :molecule, :posologie, :dateDebut, :dateFin)
                        """)
                .param("id", id)
                .param("patientId", patientId)
                .param("molecule", t.molecule())
                .param("posologie", t.posologie())
                .param("dateDebut", LocalDate.parse(t.dateDebut()))
                .param("dateFin", dateOptionnelle(t.dateFin()))
                .update();
        return ResponseEntity.ok(Map.of("id", id.toString()));
    }

    @PutMapping("/traitements/{id}")
    public ResponseEntity<Void> modifierTraitement(@PathVariable UUID id, @RequestBody TraitementEntree t) {
        int n = jdbc.sql("""
                        update traitement set molecule = :molecule, posologie = :posologie,
                               date_debut = :dateDebut, date_fin = :dateFin
                        where id = :id
                        """)
                .param("molecule", t.molecule())
                .param("posologie", t.posologie())
                .param("dateDebut", LocalDate.parse(t.dateDebut()))
                .param("dateFin", dateOptionnelle(t.dateFin()))
                .param("id", id)
                .update();
        return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    @DeleteMapping("/traitements/{id}")
    public ResponseEntity<Void> supprimerTraitement(@PathVariable UUID id) {
        int n = jdbc.sql("delete from traitement where id = :id").param("id", id).update();
        return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    // --- Séjours -------------------------------------------------------------------------------

    public record SejourEntree(String numero, String type, String dateEntree, String dateSortie, String service,
                               String motif, String medecinResponsable,
                               String typeDocument, String diagnosticCim10Code, String diagnosticCim10Libelle,
                               String acteCcamCode, String acteCcamLibelle) {
    }

    @PostMapping("/patients/{patientId}/sejours")
    public ResponseEntity<Map<String, String>> creerSejour(@PathVariable UUID patientId, @RequestBody SejourEntree s) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into sejour (id, patient_id, numero, type, date_entree, date_sortie, service, motif, medecin_responsable,
                               type_document, diagnostic_cim10_code, diagnostic_cim10_libelle, acte_ccam_code, acte_ccam_libelle)
                        values (:id, :patientId, :numero, :type, :dateEntree, :dateSortie, :service, :motif, :medecinResponsable,
                               :typeDocument, :diagnosticCim10Code, :diagnosticCim10Libelle, :acteCcamCode, :acteCcamLibelle)
                        """)
                .param("id", id)
                .param("patientId", patientId)
                .param("numero", s.numero())
                .param("type", TypeSejour.valueOf(s.type()).name())
                .param("dateEntree", LocalDate.parse(s.dateEntree()))
                .param("dateSortie", dateOptionnelle(s.dateSortie()))
                .param("service", s.service())
                .param("motif", s.motif())
                .param("medecinResponsable", s.medecinResponsable())
                .param("typeDocument", typeDocumentOptionnel(s.typeDocument()))
                .param("diagnosticCim10Code", s.diagnosticCim10Code())
                .param("diagnosticCim10Libelle", s.diagnosticCim10Libelle())
                .param("acteCcamCode", s.acteCcamCode())
                .param("acteCcamLibelle", s.acteCcamLibelle())
                .update();
        return ResponseEntity.ok(Map.of("id", id.toString()));
    }

    @PutMapping("/sejours/{id}")
    public ResponseEntity<Void> modifierSejour(@PathVariable UUID id, @RequestBody SejourEntree s) {
        int n = jdbc.sql("""
                        update sejour set numero = :numero, type = :type, date_entree = :dateEntree,
                               date_sortie = :dateSortie, service = :service, motif = :motif,
                               medecin_responsable = :medecinResponsable, type_document = :typeDocument,
                               diagnostic_cim10_code = :diagnosticCim10Code, diagnostic_cim10_libelle = :diagnosticCim10Libelle,
                               acte_ccam_code = :acteCcamCode, acte_ccam_libelle = :acteCcamLibelle
                        where id = :id
                        """)
                .param("numero", s.numero())
                .param("type", TypeSejour.valueOf(s.type()).name())
                .param("dateEntree", LocalDate.parse(s.dateEntree()))
                .param("dateSortie", dateOptionnelle(s.dateSortie()))
                .param("service", s.service())
                .param("motif", s.motif())
                .param("medecinResponsable", s.medecinResponsable())
                .param("typeDocument", typeDocumentOptionnel(s.typeDocument()))
                .param("diagnosticCim10Code", s.diagnosticCim10Code())
                .param("diagnosticCim10Libelle", s.diagnosticCim10Libelle())
                .param("acteCcamCode", s.acteCcamCode())
                .param("acteCcamLibelle", s.acteCcamLibelle())
                .param("id", id)
                .update();
        return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sejours/{id}")
    public ResponseEntity<Void> supprimerSejour(@PathVariable UUID id) {
        int n = jdbc.sql("delete from sejour where id = :id").param("id", id).update();
        return n == 0 ? ResponseEntity.notFound().build() : ResponseEntity.noContent().build();
    }

    private static LocalDate dateOptionnelle(String valeur) {
        return (valeur == null || valeur.isBlank()) ? null : LocalDate.parse(valeur);
    }

    private static String typeDocumentOptionnel(String valeur) {
        return (valeur == null || valeur.isBlank()) ? null : TypeDocument.valueOf(valeur).name();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalide(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
