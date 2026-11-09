package fr.ftoumhub.poccr.domaine.port;

import fr.ftoumhub.poccr.domaine.validation.NomDocument;
import fr.ftoumhub.poccr.domaine.validation.Validation;

import java.util.Optional;

/** Port secondaire : conservation des validations. */
public interface ArchiveValidations {

    Optional<Validation> deDocument(NomDocument document);

    void enregistrer(Validation validation);
}
