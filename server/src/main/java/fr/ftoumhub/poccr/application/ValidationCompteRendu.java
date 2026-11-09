package fr.ftoumhub.poccr.application;

import fr.ftoumhub.poccr.domaine.port.ArchiveValidations;
import fr.ftoumhub.poccr.domaine.port.ExecuteurRequetesDossier;
import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import fr.ftoumhub.poccr.domaine.validation.Validation;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Cas d'utilisation : valider un compte rendu, puis sceller le docx qui en résulte.
 */
public class ValidationCompteRendu {

    private final ExecuteurRequetesDossier executeur;
    private final ArchiveValidations archive;
    private final Clock horloge;

    public ValidationCompteRendu(ExecuteurRequetesDossier executeur, ArchiveValidations archive, Clock horloge) {
        this.executeur = executeur;
        this.archive = archive;
        this.horloge = horloge;
    }

    public Validation valider(NomDocument document, UUID patientId, String requete, String validePar) {
        if (archive.deDocument(document).isPresent()) {
            throw new CompteRenduDejaValide(document);
        }
        String donnees = executeur.executer(requete, patientId);
        Validation validation = Validation.builder()
                .document(document.valeur())
                .patientId(patientId)
                .requete(requete)
                .donneesJson(donnees)
                .valideLe(Instant.now(horloge))
                .validePar(validePar)
                .construire();
        archive.enregistrer(validation);
        return validation;
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
}
