package fr.ftoumhub.poccr.infrastructure.editeur;

import fr.ftoumhub.poccr.application.ConsultationDocuments;
import fr.ftoumhub.poccr.application.ConsultationDossierPatient;
import fr.ftoumhub.poccr.application.GestionDocuments;
import fr.ftoumhub.poccr.application.ValidationCompteRendu;
import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.Destinataire;
import fr.ftoumhub.poccr.domaine.document.DestinataireExterieur;
import fr.ftoumhub.poccr.domaine.document.DocumentFige;
import fr.ftoumhub.poccr.domaine.document.DocumentIntrouvable;
import fr.ftoumhub.poccr.domaine.document.Signataire;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Signataires, destinataires et patient d'un document (page hôte et plugin).
 * Un compte rendu validé est figé : toute modification renvoie 409.
 */
@RestController
@RequestMapping("/api")
public class ControleurActeurs {

    private final GestionDocuments gestion;
    private final ConsultationDocuments consultation;
    private final ConsultationDossierPatient dossiers;
    private final ValidationCompteRendu validation;

    public ControleurActeurs(GestionDocuments gestion, ConsultationDocuments consultation,
                             ConsultationDossierPatient dossiers, ValidationCompteRendu validation) {
        this.gestion = gestion;
        this.consultation = consultation;
        this.dossiers = dossiers;
        this.validation = validation;
    }

    public record ProfessionnelResume(UUID id, String code, String libelle, String specialites, String typeMedecin) {
        static ProfessionnelResume de(ProfessionnelSante p) {
            return new ProfessionnelResume(p.id(), p.code(), p.libelle(), p.specialites(), p.type().name());
        }
    }

    public record Exterieur(String nom, String prenom, String email) {
    }

    /** Acteurs d'un document, avec les libellés des professionnels pour l'affichage. */
    public record VueActeurs(List<ProfessionnelResume> signataires,
                             ProfessionnelResume destinatairePrincipal,
                             List<ProfessionnelResume> copies,
                             List<Exterieur> exterieurs,
                             boolean copieAuPatient,
                             boolean modifiable) {
    }

    /** Saisie complète : remplace tous les acteurs. Le premier signataire est le signataire principal. */
    public record SaisieActeurs(List<UUID> signataires,
                                UUID destinatairePrincipal,
                                List<UUID> copies,
                                List<Exterieur> exterieurs,
                                boolean copieAuPatient) {
    }

    public record RattachementPatient(UUID patientId) {
    }

    @GetMapping("/professionnels")
    public List<ProfessionnelResume> rechercherProfessionnels(@RequestParam String texte) {
        return consultation.rechercherProfessionnels(texte).stream().map(ProfessionnelResume::de).toList();
    }

    @GetMapping("/documents/{nom}/acteurs")
    public VueActeurs acteurs(@PathVariable String nom) {
        NomDocument document = new NomDocument(nom);
        ActeursDocument acteurs = gestion.acteurs(document);
        Map<UUID, ProfessionnelSante> ps = consultation.professionnelsDe(acteurs.professionnels());
        return new VueActeurs(
                acteurs.signataires().stream().map(Signataire::professionnelId).map(id -> resume(ps, id)).toList(),
                acteurs.destinatairePrincipal().map(d -> resume(ps, d.professionnelId())).orElse(null),
                acteurs.copies().stream().map(d -> resume(ps, d.professionnelId())).toList(),
                acteurs.exterieurs().stream().map(Destinataire::exterieur)
                        .map(e -> new Exterieur(e.nom(), e.prenom(), e.email())).toList(),
                acteurs.copieAuPatient(),
                validation.etat(document).isEmpty());
    }

    @PutMapping("/documents/{nom}/acteurs")
    public ResponseEntity<Void> definirActeurs(@PathVariable String nom, @RequestBody SaisieActeurs saisie) {
        ActeursDocument acteurs = ActeursDocument.saisis(
                ou(saisie.signataires()), saisie.destinatairePrincipal(), ou(saisie.copies()),
                ou(saisie.exterieurs()).stream().map(e -> new DestinataireExterieur(e.nom(), e.prenom(), e.email()))
                        .toList(),
                saisie.copieAuPatient());
        gestion.definirActeurs(new NomDocument(nom), acteurs);
        return ResponseEntity.noContent().build();
    }

    /** Appelé par le plugin au verrouillage du patient ; le patient doit exister. */
    @PutMapping("/documents/{nom}/patient")
    public ResponseEntity<Void> rattacherPatient(@PathVariable String nom, @RequestBody RattachementPatient demande) {
        if (demande.patientId() == null || dossiers.patient(demande.patientId()).isEmpty()) {
            throw new IllegalArgumentException("Patient inconnu : " + demande.patientId());
        }
        gestion.rattacherPatient(new NomDocument(nom), demande.patientId());
        return ResponseEntity.noContent().build();
    }

    private static ProfessionnelResume resume(Map<UUID, ProfessionnelSante> ps, UUID id) {
        ProfessionnelSante p = ps.get(id);
        return p == null ? new ProfessionnelResume(id, null, "(professionnel inconnu)", null, null)
                : ProfessionnelResume.de(p);
    }

    private static <T> List<T> ou(List<T> liste) {
        return liste == null ? List.of() : liste;
    }

    @ExceptionHandler(DocumentFige.class)
    ResponseEntity<Map<String, String>> fige(DocumentFige e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(DocumentIntrouvable.class)
    ResponseEntity<Map<String, String>> introuvable(DocumentIntrouvable e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    /** {@link fr.ftoumhub.poccr.domaine.document.ActeursInvalides} (règle métier), nom de document ou patient invalide. */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalide(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
