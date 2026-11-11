package fr.ftoumhub.poccr.application;

import fr.ftoumhub.poccr.domaine.document.ActeursDocument;
import fr.ftoumhub.poccr.domaine.document.Document;
import fr.ftoumhub.poccr.domaine.document.DocumentIntrouvable;
import fr.ftoumhub.poccr.domaine.document.Signataire;
import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.port.Documents;
import fr.ftoumhub.poccr.domaine.port.ExecuteurRequetesDossier;
import fr.ftoumhub.poccr.domaine.port.ProfessionnelsSante;
import fr.ftoumhub.poccr.domaine.professionnel.ProfessionnelSante;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import fr.ftoumhub.poccr.domaine.validation.Validation;
import fr.ftoumhub.poccr.domaine.validation.ValidationEnCours;
import fr.ftoumhub.poccr.domaine.validation.VariablesDocument;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Cas d'utilisation : valider un compte rendu, puis sceller le docx qui en résulte.
 *
 * Prérequis : un signataire principal, et un patient — celui de la demande doit être celui du
 * document ; un document qui n'en a pas encore reçoit celui de la demande. Le valideur est le
 * signataire principal (en attendant l'identité réelle de l'utilisateur). La date de validation
 * est fixée avant d'exécuter la requête, pour être figée avec les données.
 */
public class ValidationCompteRendu {

    private final ExecuteurRequetesDossier executeur;
    private final ArchiveValidations archive;
    private final Documents documents;
    private final ProfessionnelsSante professionnels;
    private final Clock horloge;

    public ValidationCompteRendu(ExecuteurRequetesDossier executeur, ArchiveValidations archive, Documents documents,
                                 ProfessionnelsSante professionnels, Clock horloge) {
        this.executeur = executeur;
        this.archive = archive;
        this.documents = documents;
        this.professionnels = professionnels;
        this.horloge = horloge;
    }

    public Validation valider(NomDocument nom, UUID patientId, String requete) {
        if (archive.deDocument(nom).isPresent()) {
            throw new CompteRenduDejaValide(nom);
        }
        Document document = documents.parNom(nom).orElseThrow(() -> new DocumentIntrouvable(nom));
        if (patientId == null) {
            throw new ValidationImpossible("Aucun patient : un compte rendu concerne toujours un patient");
        }
        if (document.patientId() != null && !document.patientId().equals(patientId)) {
            throw new ValidationImpossible("Le compte rendu " + nom + " concerne un autre patient que celui des données");
        }
        ProfessionnelSante valideur = valideur(document);
        Instant valideLe = Instant.now(horloge);

        String donnees = executeur.executer(requete, new VariablesDocument(document.id(), patientId),
                new ValidationEnCours(document.id(), valideLe, valideur.id()));

        if (document.patientId() == null) {
            documents.enregistrer(document.avecPatient(patientId));
        }
        Validation validation = Validation.builder()
                .document(nom.valeur())
                .documentId(document.id())
                .patientId(patientId)
                .requete(requete)
                .donneesJson(donnees)
                .valideLe(valideLe)
                .validePar(valideur.libelle())
                .valideurId(valideur.id())
                .construire();
        archive.enregistrer(validation);
        return validation;
    }

    private ProfessionnelSante valideur(Document document) {
        ActeursDocument acteurs = documents.acteursDe(Set.of(document.id()))
                .getOrDefault(document.id(), ActeursDocument.AUCUN);
        UUID principal = acteurs.signatairePrincipal().map(Signataire::professionnelId)
                .orElseThrow(() -> new ValidationImpossible(
                        "Le compte rendu " + document.nom() + " n'a pas de signataire principal"));
        ProfessionnelSante ps = professionnels.parIds(Set.of(principal)).get(principal);
        if (ps == null) {
            throw new IllegalStateException("Signataire principal introuvable : " + principal);
        }
        return ps;
    }

    public Optional<Validation> etat(NomDocument document) {
        return archive.deDocument(document);
    }

    /**
     * Décide si une sauvegarde du docx doit être écrite.
     * Document non validé : oui. Validé non scellé : oui, et cette version est scellée.
     * Déjà scellé : non, le compte rendu ne bouge plus.
     */
    public boolean autoriserSauvegarde(NomDocument document, byte[] docx) {
        Optional<Validation> validation = archive.deDocument(document);
        if (validation.isEmpty()) {
            return true;
        }
        if (validation.get().estScelle()) {
            return false;
        }
        archive.enregistrer(validation.get().scellerDocx(docx));
        return true;
    }

    public static final class CompteRenduDejaValide extends RuntimeException {
        public CompteRenduDejaValide(NomDocument document) {
            super("Le compte rendu " + document + " est déjà validé");
        }
    }

    /** Un prérequis de la validation manque (signataire principal, patient). */
    public static final class ValidationImpossible extends RuntimeException {
        public ValidationImpossible(String message) {
            super(message);
        }
    }
}
